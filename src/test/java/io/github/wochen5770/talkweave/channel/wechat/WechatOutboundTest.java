package io.github.wochen5770.talkweave.channel.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wochen5770.talkweave.conversation.*;
import io.github.wochen5770.talkweave.assistant.AssistantService;
import io.github.wochen5770.talkweave.persistence.*;
import io.github.wochen5770.talkweave.runtime.*;
import io.github.wochen5770.talkweave.support.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.wochen5770.talkweave.persistence.ConversationRepository.*;
import static org.assertj.core.api.Assertions.*;

class WechatOutboundTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private ConversationRepository repo(SqliteStore store, FakeHttpService fake) {
        var repo = new ConversationRepository(store, new Binding("bot", "owner"), TestProperties.valid(temp).storage());
        repo.installSession("bot", fake.baseUri().toString(), "fake-token", "scanner"); return repo;
    }
    private WechatApiClient client(FakeHttpService fake, long timeoutMs) {
        return new WechatApiClient(fake.baseUri(), Duration.ofMillis(timeoutMs), Duration.ofSeconds(3), fake.baseUri()::equals);
    }
    private void accept(ConversationRepository repo, String context) {
        var session = repo.session().orElseThrow();
        repo.acceptBatch(session.generation(), session.cursor(), "next", List.of(new Inbound("1", "owner", "question", context, false, true, true, true)));
    }
    private ConversationWorker worker(ConversationRepository repo, WechatOutbound outbound, AtomicInteger calls, boolean typing) {
        AssistantService model = prompt -> { calls.incrementAndGet(); return new AssistantService.Reply("answer", false); };
        return new ConversationWorker(repo, model, outbound, new ContextBudget(TestProperties.valid(temp).model()), new ReplyFormatter(2000), typing ? outbound : work -> () -> { });
    }
    @Test void businessErrorIsNotDeliverySuccessAndMissingContextNeverCallsAnything() throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake, 500); var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            var repo = repo(store, fake); var calls = new AtomicInteger(); var worker = worker(repo, new WechatOutbound(repo, client), calls, false);
            accept(repo, "fake-context"); assertThat(worker.step()).isTrue(); fake.enqueue(200, "{\"ret\":42}"); assertThat(worker.step()).isTrue();
            var sent = json.readTree(fake.take().body()).path("msg");
            assertThat(sent.path("client_id").asText()).startsWith("reply-");
            assertThat(sent.path("context_token").asText()).isEqualTo("fake-context");
            assertThat(sent.path("to_user_id").asText()).isEqualTo("owner");
            assertThat(repo.uncertainCount()).isZero(); assertThat(repo.pendingCount()).isZero();
            assertThat(worker.step()).isFalse(); assertThat(calls).hasValue(1); assertThat(fake.pendingRequests()).isZero();
            var session = repo.session().orElseThrow();
            repo.acceptBatch(session.generation(), "next", "last", List.of(new Inbound("2", "owner", "second", "", false, true, true, true)));
            assertThat(worker.step()).isFalse(); assertThat(calls).hasValue(1);
        }
    }
    @Test void dispatchedTimeoutIsUncertainAndNeverResendsOrRegeneratesAfterRestart() throws Exception {
        var calls = new AtomicInteger();
        try (var fake = new FakeHttpService(); var client = client(fake, 100)) {
            try (var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
                var repo = repo(store, fake); var worker = worker(repo, new WechatOutbound(repo, client), calls, false);
                accept(repo, "fake-context"); worker.step(); fake.enqueueDelayed(200, "{\"ret\":0}", 400); worker.step(); fake.take();
                assertThat(repo.uncertainCount()).isEqualTo(1); assertThat(worker.step()).isFalse();
            }
            try (var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
                var repo = new ConversationRepository(store, new Binding("bot", "owner"), TestProperties.valid(temp).storage());
                assertThat(worker(repo, new WechatOutbound(repo, client), calls, false).step()).isFalse();
                assertThat(calls).hasValue(1); assertThat(fake.pendingRequests()).isZero();
            }
        }
    }
    @Test void changedGenerationAndForgedRecipientCannotSendWithCapturedCredentials() throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake, 500); var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            var repo = repo(store, fake); var captured = repo.session().orElseThrow(); accept(repo, "fake-context");
            var processing = repo.claimNext().orElseThrow(); repo.saveReply(processing.sequence(), ModelStatus.SUCCEEDED, "answer", "answer");
            var work = repo.claimNext().orElseThrow(); var outbound = new WechatOutbound(repo, client);
            var forged = new Work(work.sequence(), work.botId(), "another-owner", work.generation(), work.conversationId(), work.text(), work.contextToken(), work.kind(), work.stage(), work.modelStatus(), work.replyText(), work.clientId());
            assertThatThrownBy(() -> outbound.send(captured, forged)).isInstanceOf(StorageProblem.class);
            repo.installSession("different-bot", fake.baseUri().toString(), "different-token", "scanner");
            assertThatThrownBy(() -> outbound.send(captured, work)).isInstanceOf(StorageProblem.class);
            assertThat(fake.pendingRequests()).isZero();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void typingIsBestEffortUsesCurrentContextAndAlwaysAttemptsCancel(boolean startFails) throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake, 500); var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            var repo = repo(store, fake); var calls = new AtomicInteger(); var outbound = new WechatOutbound(repo, client);
            accept(repo, "fake-context");
            fake.enqueue(200, "{\"ret\":0,\"typing_ticket\":\"fake-ticket\"}");
            fake.enqueue(startFails ? 503 : 200, "{\"ret\":0}"); fake.enqueue(200, "{\"ret\":0}");
            var worker = worker(repo, outbound, calls, true); worker.step();
            var config = fake.take(); assertThat(config.uri().getPath()).isEqualTo("/ilink/bot/getconfig");
            assertThat(json.readTree(config.body()).path("context_token").asText()).isEqualTo("fake-context");
            var start = json.readTree(fake.take().body()); var stop = json.readTree(fake.take().body());
            assertThat(start.path("status").asInt()).isEqualTo(1); assertThat(stop.path("status").asInt()).isEqualTo(2);
            assertThat(stop.path("typing_ticket").asText()).isEqualTo("fake-ticket"); assertThat(stop.path("ilink_user_id").asText()).isEqualTo("owner");
            fake.enqueue(200, "{\"ret\":0}"); worker.step(); fake.take(); assertThat(calls).hasValue(1); assertThat(repo.pendingCount()).isZero();
        }
    }
    @Test void auxiliaryFailureDoesNotBlockModelAndTicketIsNotReusedAfterLoginChanges() throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake, 500); var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            var repo = repo(store, fake); var calls = new AtomicInteger(); var outbound = new WechatOutbound(repo, client);
            accept(repo, "fake-context"); fake.enqueue(500, "{}"); worker(repo, outbound, calls, true).step(); fake.take(); assertThat(calls).hasValue(1);
            // A separate pending model turn exercises the old ticket's end callback after a generation switch.
            var ready = repo.claimNext().orElseThrow(); repo.finishSend(ready.sequence(), Delivery.CONFIRMED);
            var session = repo.session().orElseThrow();
            repo.acceptBatch(session.generation(), "next", "last", List.of(new Inbound("2", "owner", "second", "new-context", false, true, true, true)));
            var work = repo.claimNext().orElseThrow(); fake.enqueue(200, "{\"typing_ticket\":\"old-ticket\"}"); fake.enqueue(200, "{}");
            Runnable end = outbound.begin(work); fake.take(); fake.take();
            repo.installSession("different-bot", fake.baseUri().toString(), "new-token", "scanner"); end.run(); assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void lifecycleUsesDedicatedEndpointsOncePerGenerationAndShortTimeout() throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake, 8000); var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            var repo = repo(store, fake); var outbound = new WechatOutbound(repo, client); var session = repo.session().orElseThrow();
            fake.enqueue(503, "{}"); outbound.connected(session); outbound.connected(session);
            var request = fake.take(); assertThat(request.uri().getPath()).isEqualTo("/ilink/bot/msg/notifystart");
            assertThat(json.readTree(request.body()).size()).isEqualTo(1); assertThat(fake.pendingRequests()).isZero();
            fake.enqueueDelayed(200, "{}", 2300); long start = System.nanoTime(); outbound.stopping();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2250));
            assertThat(fake.take().uri().getPath()).isEqualTo("/ilink/bot/msg/notifystop");
        }
    }
}
