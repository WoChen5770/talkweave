package io.github.wochen5770.talkweave.managed.runtime;

import io.github.wochen5770.talkweave.assistant.AssistantService;
import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import io.github.wochen5770.talkweave.managed.persistence.ManagedSettings;
import io.github.wochen5770.talkweave.model.*;
import io.github.wochen5770.talkweave.support.TestProperties;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ManagedModelPoolTest {
    @Test void concurrentUsersShareVersionAndNewVersionDoesNotCloseLeasedClient() throws Exception {
        var opened=new ConcurrentHashMap<Long,AtomicInteger>(); var closed=new ConcurrentHashMap<Long,AtomicInteger>();
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor(); var pool=new ManagedModelPool(snapshot -> {
            opened.computeIfAbsent(snapshot.version(),k -> new AtomicInteger()).incrementAndGet();
            return new ManagedModelPool.Client() {
                public AssistantService.Reply answer(ModelRequestContext context,List<DialogueMessage> messages,ModelAttemptObserver observer) {
                    if (snapshot.version()==1) { entered.countDown(); try { if (!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("lease timeout"); } catch (InterruptedException e) { throw new AssertionError(e); } }
                    return new AssistantService.Reply("synthetic",false);
                }
                public void close() { closed.computeIfAbsent(snapshot.version(),k -> new AtomicInteger()).incrementAndGet(); }
            };
        })) {
            var a=executor.submit(() -> call(pool,1,"a")); var b=executor.submit(() -> call(pool,1,"b"));
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue(); call(pool,2,"c");
            assertThat(opened.get(1L).get()).isEqualTo(1); assertThat(closed).doesNotContainKey(1L);
            release.countDown(); a.get(3,TimeUnit.SECONDS); b.get(3,TimeUnit.SECONDS);
            assertThat(closed.get(1L).get()).isEqualTo(1); assertThat(closed).doesNotContainKey(2L);
            call(pool,2,"d"); assertThat(opened.get(2L).get()).isEqualTo(1);
        } finally { release.countDown(); }
        assertThat(closed.get(2L).get()).isEqualTo(1);
    }
    @Test void shutdownRejectsNewRequestsAndClosesIdleClientOnce() {
        var count=new AtomicInteger();
        var pool=new ManagedModelPool(snapshot -> new ManagedModelPool.Client() {
            public AssistantService.Reply answer(ModelRequestContext c,List<DialogueMessage> m,ModelAttemptObserver o) { return new AssistantService.Reply("ok",false); }
            public void close() { count.incrementAndGet(); }
        });
        call(pool,1,"a"); pool.close(); pool.close();
        assertThat(count.get()).isEqualTo(1); assertThatThrownBy(() -> call(pool,1,"a")).isInstanceOf(IllegalStateException.class);
    }
    private static void call(ManagedModelPool pool,long version,String user) {
        var snapshot=new ManagedSettings.ModelSnapshot(version,TestProperties.model("https://model.invalid","synthetic-key"));
        pool.answer(snapshot,new ModelRequestContext(user,"binding-"+user,"conversation-"+user,1,1,1,version),
                List.of(new DialogueMessage(DialogueMessage.Role.USER,"hello")),ModelAttemptObserver.noop());
    }
}
