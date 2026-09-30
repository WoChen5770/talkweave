package io.github.wochen5770.talkweave.managed.runtime;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedShutdownTest {
    @Test void resourcesCloseConcurrentlyAfterFencesUnderOneDeadline() {
        var shutdown=new ManagedShutdown(Duration.ofSeconds(1));
        var fenced=new AtomicInteger(); var started=new CountDownLatch(4); var completed=new AtomicInteger();
        for(int i=0;i<4;i++) shutdown.register("component-"+i,fenced::incrementAndGet,()-> {
            assertEquals(4,fenced.get()); started.countDown();
            assertTrue(started.await(500,TimeUnit.MILLISECONDS)); completed.incrementAndGet();
        },false);
        shutdown.register("materials",()->{},()->assertEquals(4,completed.get()),true);
        shutdown.destroy(); shutdown.destroy();
        assertTrue(shutdown.result().complete()); assertEquals(4,completed.get());
    }
    @Test void blockedAndFailedClosersAreReportedWithoutMultiplyingBudget() {
        var shutdown=new ManagedShutdown(Duration.ofMillis(100)); var release=new CountDownLatch(1);
        var materialClosed=new AtomicBoolean();
        for(int i=0;i<4;i++) shutdown.register("slow-"+i,()->{},()-> {
            while(release.getCount()>0) try { release.await(); } catch(InterruptedException ignored) { }
        },false);
        shutdown.register("failed",()->{},()-> { throw new IllegalStateException("SECRET-NEVER-LOG"); },false);
        shutdown.register("materials",()->{},()->materialClosed.set(true),true);
        try {
            long start=System.nanoTime(); shutdown.destroy();
            assertTrue(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(500));
            assertFalse(shutdown.result().complete()); assertEquals(5,shutdown.result().unfinished().size());
            assertEquals(java.util.List.of("failed"),shutdown.result().failed()); assertFalse(materialClosed.get());
            assertFalse(shutdown.result().toString().contains("SECRET"));
        } finally { release.countDown(); }
    }
    @Test void failedCloseKeepsMaterialLockEvenWhenAllFuturesHaveFinished() {
        var shutdown=new ManagedShutdown(Duration.ofSeconds(1));
        var materialClosed=new AtomicBoolean();
        shutdown.register("qr",()->{},()-> { throw new IllegalStateException("not stopped"); },false);
        shutdown.register("materials",()->{},()->materialClosed.set(true),true);
        shutdown.destroy();
        assertFalse(shutdown.result().complete()); assertFalse(materialClosed.get());
        assertEquals(java.util.List.of("qr"),shutdown.result().failed());
        assertEquals(java.util.List.of("materials"),shutdown.result().unfinished());
    }
    @Test void exceptionalFutureCannotMasqueradeAsSuccessfulCleanup() {
        var shutdown=new ManagedShutdown(Duration.ofSeconds(1));
        shutdown.register("model",()->{},()-> { throw new AssertionError("private detail"); },false);
        shutdown.destroy();
        assertFalse(shutdown.result().complete());
        assertEquals(java.util.List.of("model"),shutdown.result().failed());
        assertFalse(shutdown.result().toString().contains("private detail"));
    }
}
