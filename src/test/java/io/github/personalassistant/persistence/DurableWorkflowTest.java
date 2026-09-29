package io.github.personalassistant.persistence;

import io.github.personalassistant.assistant.AssistantService;
import io.github.personalassistant.channel.wechat.InboxReceiver;
import io.github.personalassistant.conversation.ContextBudget;
import io.github.personalassistant.conversation.ConversationWorker;
import io.github.personalassistant.conversation.DialogueMessage;
import io.github.personalassistant.conversation.ReplyFormatter;
import io.github.personalassistant.runtime.AssistantProperties;
import io.github.personalassistant.runtime.RemoteFailure;
import io.github.personalassistant.support.TestProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.personalassistant.persistence.ConversationRepository.*;
import static org.assertj.core.api.Assertions.*;

class DurableWorkflowTest {
    @TempDir Path directory;
    private AssistantProperties.Storage config() { return TestProperties.valid(directory).storage(); }
    private ConversationRepository repository(SqliteStore store) { return new ConversationRepository(store, new Binding("bot", "owner"), config()); }
    private void login(ConversationRepository repo) { repo.installSession("bot", "https://example.invalid", "synthetic-token", "scanner"); }
    private Inbound message(String id, String text) { return new Inbound(id, "owner", text, "synthetic-context", false, true, true, true); }
    private void accept(ConversationRepository repo, Inbound... messages) {
        Session s = repo.session().orElseThrow();
        assertThat(repo.acceptBatch(s.generation(), s.cursor(), s.cursor() + "x", List.of(messages))).isTrue();
    }
    private ConversationWorker worker(ConversationRepository repo, AssistantService model, ConversationWorker.ReplySender sender) {
        return new ConversationWorker(repo, model, sender, new ContextBudget(TestProperties.model("https://model.invalid", "fake-key")), new ReplyFormatter(128));
    }
    private void drain(ConversationWorker worker) { for (int i = 0; i < 30; i++) if (!worker.step()) return; fail("Queue did not drain"); }
    private void queryOnly(SqliteStore store, boolean enabled) {
        store.transaction(c -> { try (var sql = c.createStatement()) { sql.execute("PRAGMA query_only=" + (enabled ? "ON" : "OFF")); } return null; });
    }

    @Test void receivingAndOrderedConsumerAreIndependentAndNewSeparatesQueuedContexts() throws Exception {
        try (var store = SqliteStore.open(config()); var executor = Executors.newSingleThreadExecutor()) {
            var repo = repository(store); login(repo); accept(repo, message("1", "A"));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var prompts = java.util.Collections.synchronizedList(new ArrayList<List<DialogueMessage>>());
            var sends = new ArrayList<String>();
            var worker = worker(repo, prompt -> {
                prompts.add(prompt);
                if (prompts.size() == 1) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test timed out"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                }
                return new AssistantService.Reply("answer-" + prompt.getLast().text(), false);
            }, (s, w) -> sends.add(w.replyText()));
            var first = executor.submit(worker::step);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var receiver = new InboxReceiver(repo, s -> new InboxReceiver.Batch("b", List.of(message("2", "/new"), message("3", "B"))));
                assertThat(receiver.receiveOnce()).isEqualTo(InboxReceiver.Result.SAVED);
                assertThat(repo.pendingCount()).isEqualTo(3);
                assertThat(worker.step()).isFalse();
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            drain(worker);
            assertThat(prompts).hasSize(2);
            assertThat(prompts.get(1)).extracting(DialogueMessage::text).containsExactly("You are a test assistant.", "B");
            assertThat(sends).hasSize(3);
            assertThat(sends.get(0)).isEqualTo("answer-A");
            assertThat(sends.get(2)).isEqualTo("answer-B");
        }
    }

    @Test void responseReadyRestartSendsStoredResultWithoutSecondModelCall() {
        var models = new AtomicInteger(); var sends = new AtomicInteger();
        AssistantService model = prompt -> { models.incrementAndGet(); return new AssistantService.Reply("stored-answer", false); };
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store); login(repo); accept(repo, message("1", "question"));
            assertThat(worker(repo, model, (s, w) -> sends.incrementAndGet()).step()).isTrue();
            assertThat(models).hasValue(1); assertThat(sends).hasValue(0);
        }
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store);
            drain(worker(repo, model, (s, w) -> { assertThat(w.replyText()).isEqualTo("stored-answer"); sends.incrementAndGet(); }));
            assertThat(models).hasValue(1); assertThat(sends).hasValue(1);
        }
    }

    @Test void lostSendResponseIsUnknownAndIsNotRetriedOrUsedAsHistory() {
        var models = new AtomicInteger(); var sends = new AtomicInteger();
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store); login(repo); accept(repo, message("1", "question"));
            drain(worker(repo, prompt -> { models.incrementAndGet(); return new AssistantService.Reply("answer", false); }, (s, w) -> {
                sends.incrementAndGet(); throw new RemoteFailure(RemoteFailure.Source.WECHAT, RemoteFailure.Kind.TIMEOUT);
            }));
            assertThat(repo.uncertainCount()).isEqualTo(1);
        }
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store); accept(repo, message("1", "duplicate"), message("2", "next"));
            drain(worker(repo, prompt -> { assertThat(prompt).hasSize(2); models.incrementAndGet(); return new AssistantService.Reply("next-answer", false); }, (s, w) -> sends.incrementAndGet()));
            assertThat(models).hasValue(2); assertThat(sends).hasValue(2);
        }
    }

    @Test void failureToSaveGeneratedResultLeavesProcessingAndNeverRepeatsModelOnRestart() {
        var models = new AtomicInteger();
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store); login(repo); accept(repo, message("1", "question"));
            var worker = worker(repo, prompt -> { models.incrementAndGet(); queryOnly(store, true); return new AssistantService.Reply("not-persisted", false); }, (s, w) -> fail("Must not send"));
            assertThatThrownBy(worker::step).isInstanceOf(StorageProblem.class);
            queryOnly(store, false);
        }
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store);
            drain(worker(repo, prompt -> { models.incrementAndGet(); throw new AssertionError("Must not repeat model"); }, (s, w) -> fail("Must not send")));
            assertThat(models).hasValue(1);
            assertThat(repo.uncertainCount()).isEqualTo(1);
        }
    }

    @Test void writeFailureAfterSuccessfulSendBecomesUnknownOnRestart() {
        var sends = new AtomicInteger();
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store); login(repo); accept(repo, message("1", "question"));
            var worker = worker(repo, prompt -> new AssistantService.Reply("answer", false), (s, w) -> { sends.incrementAndGet(); queryOnly(store, true); });
            worker.step();
            assertThatThrownBy(worker::step).isInstanceOf(StorageProblem.class);
            queryOnly(store, false);
        }
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store);
            assertThat(worker(repo, p -> { throw new AssertionError("No replay"); }, (s, w) -> sends.incrementAndGet()).step()).isFalse();
            assertThat(repo.uncertainCount()).isEqualTo(1);
            assertThat(sends).hasValue(1);
        }
    }

    @Test void commandsMediaAndInputOverBudgetNeverCallModelAndAllUseProtectedDelivery() {
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store); login(repo);
            accept(repo, message("1", "/help"), message("2", "/unknown"), new Inbound("3", "owner", null, "ctx", false, true, true, false), message("4", "x".repeat(10000)));
            var sends = new ArrayList<Work>();
            drain(worker(repo, p -> { throw new AssertionError("No model calls allowed"); }, (s, w) -> sends.add(w)));
            assertThat(sends).hasSize(4);
            assertThat(sends).allSatisfy(w -> { assertThat(w.stage()).isEqualTo(Stage.SENDING); assertThat(w.clientId()).isNotBlank(); assertThat(w.replyText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(128); });
            assertThat(sends.get(3).modelStatus()).isEqualTo(ModelStatus.FAILED);
        }
    }

    @Test void receiverDoesNotPollWhenDatabaseIsReadOnlyOrQueueIsFullAndRecovers() {
        var config = new AssistantProperties.Storage(directory.toString(), 1, 0, java.time.Duration.ofSeconds(5));
        try (var store = SqliteStore.open(config)) {
            var repo = new ConversationRepository(store, new Binding("bot", "owner"), config); login(repo);
            var polls = new AtomicInteger();
            var receiver = new InboxReceiver(repo, s -> { int n = polls.incrementAndGet(); return new InboxReceiver.Batch("c" + n, List.of(message("m" + n, "text"))); });
            queryOnly(store, true);
            assertThat(receiver.receiveOnce()).isEqualTo(InboxReceiver.Result.PAUSED);
            assertThat(polls).hasValue(0);
            queryOnly(store, false);
            assertThat(receiver.receiveOnce()).isEqualTo(InboxReceiver.Result.SAVED);
            assertThat(receiver.receiveOnce()).isEqualTo(InboxReceiver.Result.PAUSED);
            assertThat(polls).hasValue(1);
            repo.failProcessing(repo.claimNext().orElseThrow().sequence());
            assertThat(receiver.receiveOnce()).isEqualTo(InboxReceiver.Result.SAVED);
            assertThat(polls).hasValue(2);
        }
    }

    @Test void inFlightBatchAndTokenErrorCannotOverwriteNewLogin() {
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store); login(repo);
            var receiver = new InboxReceiver(repo, s -> { login(repo); return new InboxReceiver.Batch("late", List.of(message("1", "old"))); });
            assertThat(receiver.receiveOnce()).isEqualTo(InboxReceiver.Result.STALE_RESPONSE);
            assertThat(repo.pendingCount()).isZero();
            var failing = new InboxReceiver(repo, s -> { login(repo); throw new RemoteFailure(RemoteFailure.Source.WECHAT, RemoteFailure.Kind.STALE_TOKEN); });
            assertThatThrownBy(failing::receiveOnce).isInstanceOf(RemoteFailure.class);
            assertThat(repo.session().orElseThrow().active()).isTrue();
            assertThat(repo.session().orElseThrow().cursor()).isEmpty();
        }
    }
    @Test void fullGenerationIsStoredButLaterHistoryContainsOnlyActuallySentTruncatedText() {
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store); login(repo); accept(repo, message("1", "first"), message("2", "second"));
            String full = "你好🙂".repeat(100);
            var calls = new AtomicInteger();
            var sent = new ArrayList<String>();
            drain(worker(repo, prompt -> {
                if (calls.incrementAndGet() == 1) return new AssistantService.Reply(full, true);
                assertThat(prompt).hasSize(4);
                assertThat(prompt.get(2).text()).isEqualTo(sent.getFirst()).isNotEqualTo(full);
                return new AssistantService.Reply("next", false);
            }, (session, work) -> sent.add(work.replyText())));
            assertThat(calls).hasValue(2);
            assertThat(sent.getFirst()).contains("模型达到输出上限", "已截断");
            store.transaction(c -> {
                try (var sql = c.createStatement(); var row = sql.executeQuery("SELECT full_result,reply_text FROM turn WHERE event_sequence=1")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo(full);
                    assertThat(row.getString(2)).isEqualTo(sent.getFirst());
                }
                return null;
            });
        }
    }

    @Test void firstModelErrorIsIsolatedAndNotIncludedInNextContext() {
        try (var store = SqliteStore.open(config())) {
            var repo = repository(store); login(repo); accept(repo, message("1", "first"), message("2", "next"));
            var calls = new AtomicInteger(); var sends = new ArrayList<String>();
            drain(worker(repo, prompt -> {
                if (calls.incrementAndGet() == 1) throw new RemoteFailure(RemoteFailure.Source.MODEL, RemoteFailure.Kind.TIMEOUT);
                assertThat(prompt).hasSize(2);
                return new AssistantService.Reply("recovered", false);
            }, (session, work) -> sends.add(work.replyText())));
            assertThat(calls).hasValue(2);
            assertThat(sends).hasSize(2);
            assertThat(sends.getLast()).isEqualTo("recovered");
        }
    }}