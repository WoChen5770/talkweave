package io.github.wochen5770.talkweave.managed.runtime;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FairUserSchedulerTest {
    @Test void aBusyUserYieldsToOtherUsersAndDuplicateWakesAreCoalesced() throws Exception {
        try (var scheduler = new FairUserScheduler(1, 3)) {
            var order = new CopyOnWriteArrayList<String>();
            var first = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(4);
            var rounds = new AtomicInteger();
            var a = scheduler.register("a", () -> {
                int round = rounds.incrementAndGet(); order.add("a" + round); done.countDown();
                if (round == 1) { first.countDown(); await(release); }
                return round < 3;
            }, () -> { throw new AssertionError("unexpected failure"); });
            var b = scheduler.register("b", () -> { order.add("b"); done.countDown(); return false; }, () -> { });
            a.wake(); assertThat(first.await(3, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 500; i++) { a.wake(); b.wake(); }
            release.countDown(); assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.stop(Duration.ofSeconds(1))).isTrue();
            assertThat(order).containsExactly("a1", "b", "a2", "a3");
        }
    }
    @Test void slowAAllowsBAndCToProgressWithinGlobalConcurrency() throws Exception {
        try (var scheduler = new FairUserScheduler(2, 3)) {
            var active = new AtomicInteger(); var maximum = new AtomicInteger();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var others = new CountDownLatch(2);
            var a = scheduler.register("a", () -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max); entered.countDown();
                try { await(release); } finally { active.decrementAndGet(); } return false;
            }, () -> { });
            var step = (java.util.function.BooleanSupplier) () -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max); active.decrementAndGet(); others.countDown(); return false;
            };
            var b = scheduler.register("b", step, () -> { }); var c = scheduler.register("c", step, () -> { });
            a.wake(); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue(); b.wake(); c.wake();
            assertThat(others.await(3, TimeUnit.SECONDS)).isTrue(); assertThat(maximum.get()).isEqualTo(2); release.countDown();
        }
    }
    @Test void failedStepIsNotReplayedAndDoesNotStopAnotherUser() throws Exception {
        try (var scheduler = new FairUserScheduler(1, 2)) {
            var attempts = new AtomicInteger(); var failed = new CountDownLatch(1); var bDone = new CountDownLatch(1);
            var a = scheduler.register("a", () -> { attempts.incrementAndGet(); throw new IllegalStateException("synthetic"); }, failed::countDown);
            var b = scheduler.register("b", () -> { bDone.countDown(); return false; }, () -> { });
            a.wake(); assertThat(failed.await(3, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50; i++) a.wake(); b.wake();
            assertThat(bDone.await(3, TimeUnit.SECONDS)).isTrue(); assertThat(attempts.get()).isEqualTo(1);
        }
    }
    @Test void closingAInterruptsOnlyAAndReservesItsSlotUntilExit() throws Exception {
        try (var scheduler = new FairUserScheduler(2, 2)) {
            var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1); var bDone = new CountDownLatch(1);
            var a = scheduler.register("a", () -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { interrupted.countDown(); }
                await(release); return false;
            }, () -> { });
            var b = scheduler.register("b", () -> { bDone.countDown(); return false; }, () -> { });
            a.wake(); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue(); a.close();
            assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> scheduler.register("a", () -> false, () -> { })).isInstanceOf(IllegalStateException.class);
            b.wake(); assertThat(bDone.await(3, TimeUnit.SECONDS)).isTrue(); release.countDown();
        }
    }
    @Test void boundsRegistrationAndShutdownDoesNotWaitIndefinitely() throws Exception {
        var scheduler = new FairUserScheduler(1, 1);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var a = scheduler.register("a", () -> {
            entered.countDown();
            boolean done = false;
            while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) { } }
            return false;
        }, () -> { });
        try {
            assertThatThrownBy(() -> scheduler.register("b", () -> false, () -> { })).isInstanceOf(IllegalStateException.class);
            a.wake(); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.stop(Duration.ZERO)).isFalse();
            assertThatThrownBy(() -> scheduler.register("b", () -> false, () -> { })).isInstanceOf(IllegalStateException.class);
        } finally { release.countDown(); scheduler.close(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test barrier timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted test barrier"); }
    }
}
