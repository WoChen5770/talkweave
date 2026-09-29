package io.github.personalassistant.runtime;

import io.github.personalassistant.assistant.AssistantService;
import io.github.personalassistant.channel.wechat.*;
import io.github.personalassistant.conversation.DialogueMessage;
import io.github.personalassistant.persistence.*;
import io.github.personalassistant.support.TestProperties;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.personalassistant.persistence.ConversationRepository.*;
import static org.assertj.core.api.Assertions.*;

class AssistantRuntimeTest {
    @TempDir Path temp;
    private final AtomicInteger qrRequests = new AtomicInteger();
    private final AtomicInteger cancelled = new AtomicInteger();
    private AssistantProperties config() {
        var base = TestProperties.valid(temp);
        return new AssistantProperties(base.model(), base.wechat(), base.storage(), Duration.ofSeconds(1));
    }
    private ConversationRepository repo(SqliteStore store, boolean bound) {
        return new ConversationRepository(store, new Binding(bound ? "bot" : "", bound ? "owner" : ""), config().storage());
    }
    private Session login(ConversationRepository repo) { return repo.installSession("bot", "https://ilinkai.weixin.qq.com", "synthetic-token", "scanner"); }
    private Inbound message(String id, String text) { return new Inbound(id, "owner", text, "synthetic-context", false, true, true, true); }
    private AssistantRuntime runtime(ConversationRepository repo, InboxReceiver.Poller poller, AssistantService model, List<String> sends) throws Exception {
        var files = new PrivateStateFiles(temp.resolve("login"), false);
        var login = new LoginCoordinator(repo, files, new LoginCoordinator.Port() {
            public WechatApiClient.QrCode requestQr() { qrRequests.incrementAndGet(); return new WechatApiClient.QrCode("fake-qr", "synthetic-display"); }
            public WechatApiClient.LoginStatus poll(URI origin, WechatApiClient.QrCode qr, String code) {
                return new WechatApiClient.LoginStatus(WechatApiClient.LoginPhase.WAIT, null, null);
            }
            public void validate(WechatApiClient.Credentials credentials) { }
        }, Clock.systemUTC(), Duration.ofMinutes(2));
        return new AssistantRuntime(repo, login, new IdentityVerifier(repo, files), new InboxReceiver(repo, poller), model,
                (session, work) -> sends.add(work.replyText()), config(), files, cancelled::incrementAndGet, 0);
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch deadline"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted"); }
    }
    @Test void waitingForScanIsLiveButNotReadyAndHealthNeverCallsModel() throws Exception {
        var modelCalls = new AtomicInteger();
        try (var store = SqliteStore.open(config().storage()); var http = HttpClient.newHttpClient()) {
            var repo = repo(store, false);
            var runtime = runtime(repo, session -> { throw new AssertionError("No session must not poll"); },
                    prompt -> { modelCalls.incrementAndGet(); return new AssistantService.Reply("answer", false); }, new CopyOnWriteArrayList<>());
            try {
                runtime.start(); until(() -> qrRequests.get() == 1 && runtime.alive());
                var live = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + runtime.healthPort() + "/health/live")).build(), HttpResponse.BodyHandlers.ofString());
                var ready = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + runtime.healthPort() + "/health/ready")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(live.statusCode()).isEqualTo(200); assertThat(ready.statusCode()).isEqualTo(503);
                assertThat(ready.body()).contains("NOT_CALLED").doesNotContain("synthetic-display", "fake-qr", "synthetic-token");
                assertThat(modelCalls).hasValue(0);
            } finally { runtime.stop(); }
            assertThat(runtime.isRunning()).isFalse(); assertThat(cancelled).hasValue(1);
            assertThat(temp.resolve("login/qr.png")).doesNotExist();
        }
    }
    @Test void restoredSessionReceivesWhileModelIsBusyAndNewSeparatesQueuedContexts() throws Exception {
        try (var store = SqliteStore.open(config().storage())) {
            var repo = repo(store, true); login(repo);
            var modelStarted = new CountDownLatch(1); var release = new CountDownLatch(1);
            var polls = new AtomicInteger();
            var prompts = new CopyOnWriteArrayList<List<DialogueMessage>>(); var sends = new CopyOnWriteArrayList<String>();
            var runtime = runtime(repo, session -> {
                int count = polls.incrementAndGet();
                if (count == 1) return new InboxReceiver.Batch("a", List.of(message("1", "question-A")));
                if (count == 2) { await(modelStarted); return new InboxReceiver.Batch("b", List.of(message("2", "/new"), message("3", "question-B"))); }
                return new InboxReceiver.Batch(session.cursor(), List.of());
            }, prompt -> {
                prompts.add(prompt);
                if (prompts.size() == 1) { modelStarted.countDown(); await(release); }
                return new AssistantService.Reply("answer-" + prompts.size(), false);
            }, sends);
            try {
                runtime.start(); until(() -> repo.pendingCount() == 3);
                assertThat(prompts).hasSize(1); assertThat(sends).isEmpty();
                release.countDown(); until(() -> sends.size() == 3);
                assertThat(prompts).hasSize(2);
                assertThat(prompts.get(1)).extracting(DialogueMessage::text).contains("question-B").doesNotContain("question-A", "answer-1");
                assertThat(sends.getFirst()).isEqualTo("answer-1"); assertThat(sends.getLast()).isEqualTo("answer-2");
                assertThat(runtime.snapshot()).containsEntry("ready", true).containsEntry("model", "SUCCEEDED");
                assertThat(qrRequests).hasValue(0);
            } finally { release.countDown(); runtime.stop(); }
        }
    }
    @Test void shutdownClassifiesModelBeforeCloseAndNeverReplaysIt() throws Exception {
        var calls = new AtomicInteger(); var modelStarted = new CountDownLatch(1); var pollStarted = new CountDownLatch(1); var pollCancelled = new CountDownLatch(1);
        try (var store = SqliteStore.open(config().storage())) {
            var repo = repo(store, true); var session = login(repo);
            repo.acceptBatch(session.generation(), "", "saved", List.of(message("1", "question")));
            var runtime = runtime(repo, captured -> {
                pollStarted.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); pollCancelled.countDown(); }
                return new InboxReceiver.Batch("late", List.of(message("2", "must-not-accept")));
            }, prompt -> { calls.incrementAndGet(); modelStarted.countDown(); await(new CountDownLatch(1)); return null; }, new CopyOnWriteArrayList<>());
            runtime.start(); await(modelStarted); await(pollStarted);
            long start = System.nanoTime(); runtime.stop();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(pollCancelled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(repo.uncertainCount()).isEqualTo(1);
            assertThat(repo.pendingCount()).isZero(); assertThat(repo.session().orElseThrow().cursor()).isEqualTo("saved");
        }
        try (var store = SqliteStore.open(config().storage())) {
            assertThat(repo(store, true).claimNext()).isEmpty(); assertThat(calls).hasValue(1);
        }
    }
    @Test void unboundRestoredBotIsDiagnosableWithoutModelOrHistory() throws Exception {
        try (var store = SqliteStore.open(config().storage())) {
            var repo = repo(store, false); login(repo);
            var runtime = runtime(repo, session -> new InboxReceiver.Batch("saved", List.of(message("1", "unauthorized-body"))),
                    prompt -> { throw new AssertionError("Unbound model call"); }, new CopyOnWriteArrayList<>());
            try {
                runtime.start(); until(() -> "CONNECTED".equals(runtime.snapshot().get("connection")));
                assertThat(runtime.snapshot()).containsEntry("bound", false).containsEntry("ready", false);
                assertThat(repo.pendingCount()).isZero();
                assertThat(Files.readString(temp.resolve("login/identity.json"))).contains("SEND_CHALLENGE").doesNotContain("unauthorized-body");
            } finally { runtime.stop(); }
        }
    }
    @Test void backoffIsCappedAndResettable() {
        var backoff = new Backoff();
        assertThat(java.util.stream.IntStream.range(0, 8).mapToLong(i -> backoff.failureDelayMillis()).toArray())
                .containsExactly(1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000);
        backoff.reset(); assertThat(backoff.failureDelayMillis()).isEqualTo(1000);
    }
    @Test void connectionBackoffAndModelDegradationRecoverWithoutHealthModelProbes() throws Exception {
        try (var store = SqliteStore.open(config().storage())) {
            var repo = repo(store, true); login(repo);
            var polls = new AtomicInteger(); var calls = new AtomicInteger();
            var runtime = runtime(repo, session -> {
                if (polls.incrementAndGet() == 1) throw new RemoteFailure(RemoteFailure.Source.WECHAT, RemoteFailure.Kind.CONNECTION);
                return new InboxReceiver.Batch(session.cursor(), List.of());
            }, prompt -> {
                if (calls.incrementAndGet() == 1) throw new RemoteFailure(RemoteFailure.Source.MODEL, RemoteFailure.Kind.HTTP, 401);
                return new AssistantService.Reply("answer", false);
            }, new CopyOnWriteArrayList<>());
            try {
                runtime.start(); until(() -> "RECONNECTING".equals(runtime.snapshot().get("connection")));
                assertThat(polls).hasValue(1); assertThat(runtime.snapshot()).containsEntry("live", true).containsEntry("ready", false);
                until(() -> "CONNECTED".equals(runtime.snapshot().get("connection")));
                var session = repo.session().orElseThrow();
                repo.acceptBatch(session.generation(), session.cursor(), "first", List.of(message("1", "first")));
                until(() -> "FAILED".equals(runtime.snapshot().get("model")) && repo.pendingCount() == 0);
                assertThat(runtime.snapshot()).containsEntry("ready", false); assertThat(calls).hasValue(1);
                session = repo.session().orElseThrow();
                repo.acceptBatch(session.generation(), session.cursor(), "second", List.of(message("2", "second")));
                until(() -> Boolean.TRUE.equals(runtime.snapshot().get("ready")));
                assertThat(calls).hasValue(2);
            } finally { runtime.stop(); }
        }
    }
    @Test void backlogAndUncertainTurnsAreVisibleWithoutBlockingTheHealthEndpoint() throws Exception {
        try (var store = SqliteStore.open(config().storage())) {
            var repo = repo(store, true); var session = login(repo);
            repo.acceptBatch(session.generation(), "", "first", List.of(message("initial", "initial")));
            var first = repo.claimNext().orElseThrow(); repo.interruptProcessing(first.sequence());
            var messages = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> message("m-" + i, "question")).toList();
            repo.acceptBatch(session.generation(), "first", "backlog", messages);
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var runtime = runtime(repo, captured -> { throw new AssertionError("Backlog must not poll"); }, prompt -> {
                started.countDown(); await(release); return new AssistantService.Reply("answer", false);
            }, new CopyOnWriteArrayList<>());
            try {
                runtime.start(); await(started);
                assertThat(runtime.snapshot()).containsEntry("live", true).containsEntry("ready", false)
                        .containsEntry("intake", "BACKLOG").containsEntry("pending", 1000L).containsEntry("uncertain", 1L);
            } finally { runtime.stop(); release.countDown(); }
        }
    }
}
