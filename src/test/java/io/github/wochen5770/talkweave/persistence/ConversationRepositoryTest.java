package io.github.wochen5770.talkweave.persistence;

import io.github.wochen5770.talkweave.runtime.AssistantProperties;
import io.github.wochen5770.talkweave.support.TestProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static io.github.wochen5770.talkweave.persistence.ConversationRepository.*;
import static org.assertj.core.api.Assertions.*;

class ConversationRepositoryTest {
    @TempDir Path directory;
    private AssistantProperties.Storage config() { return TestProperties.valid(directory).storage(); }
    private ConversationRepository repo(SqliteStore store) { return new ConversationRepository(store, new Binding("bot", "owner"), config()); }
    private Session login(ConversationRepository repository) { return repository.installSession("bot", "https://example.invalid", "synthetic-token", "scanner"); }
    private Inbound message(String id, String text) { return new Inbound(id, "owner", text, "synthetic-context", false, true, true, true); }
    private void accept(ConversationRepository repo, String cursor, Inbound... messages) {
        Session s = repo.session().orElseThrow();
        assertThat(repo.acceptBatch(s.generation(), s.cursor(), cursor, List.of(messages))).isTrue();
    }
    private Work claim(ConversationRepository repo) { return repo.claimNext().orElseThrow(); }
    private String value(SqliteStore store, String query) {
        return store.transaction(c -> { try (var s = c.createStatement(); var r = s.executeQuery(query)) { r.next(); return r.getString(1); } });
    }

    @Test void commitsBatchAndCursorTogetherAndRollsBackInjectedFailure() {
        try (var store = SqliteStore.open(config())) {
            var fail = new AtomicBoolean(true);
            var repository = new ConversationRepository(store, new Binding("bot", "owner"), config(), () -> Long.MAX_VALUE,
                    () -> { if (fail.getAndSet(false)) throw new StorageProblem(StorageProblem.Reason.DATABASE_UNAVAILABLE); });
            login(repository);
            assertThatThrownBy(() -> accept(repository, "cursor-1", message("m1", "private-body"))).isInstanceOf(StorageProblem.class);
            assertThat(repository.session().orElseThrow().cursor()).isEmpty();
            assertThat(repository.pendingCount()).isZero();
            assertThat(value(store, "SELECT count(*) FROM inbound_event")).isEqualTo("0");
            accept(repository, "cursor-1", message("m1", "private-body"));
            assertThat(repository.pendingCount()).isEqualTo(1);
            assertThat(repository.session().orElseThrow().cursor()).isEqualTo("cursor-1");
            assertThat(repository.acceptBatch(1, "old-cursor", "must-not-commit", List.of(message("m2", "second")))).isFalse();
            assertThat(repository.pendingCount()).isEqualTo(1);
        }
    }

    @Test void deduplicatesAcrossRestartButNotByTextAndDoesNotStoreUnauthorizedBody() {
        try (var store = SqliteStore.open(config())) {
            var repository = repo(store); login(repository);
            accept(repository, "a", message("1", "identical"), message("2", "identical"),
                    new Inbound("3", "stranger", "secret-stranger", "secret-context", false, true, true, true),
                    message(null, "missing-id-secret"),
                    new Inbound("4", "owner", "partial-secret", "ctx", false, true, false, true), message("4", "final-text"));
            assertThat(repository.pendingCount()).isEqualTo(3);
            assertThat(value(store, "SELECT count(*) FROM inbound_event WHERE disposition!='ACCEPTED' AND (text IS NOT NULL OR context_token IS NOT NULL)")).isEqualTo("0");
        }
        try (var store = SqliteStore.open(config())) {
            var repository = repo(store);
            accept(repository, "b", message("1", "changed-replay"), message("2", "identical"), message("5", "identical"));
            assertThat(repository.pendingCount()).isEqualTo(4);
            assertThat(value(store, "SELECT text FROM inbound_event WHERE message_id='1'")).isEqualTo("identical");
        }
    }

    @Test void singleClaimIsAtomicAndReceivingDoesNotWaitForProcessing() throws Exception {
        try (var store = SqliteStore.open(config()); var executor = Executors.newFixedThreadPool(2)) {
            var repository = repo(store); login(repository); accept(repository, "a", message("1", "first"));
            var claims = executor.invokeAll(List.of(repository::claimNext, repository::claimNext));
            int count = 0;
            for (var result : claims) if (((java.util.Optional<?>) result.get()).isPresent()) count++;
            assertThat(count).isEqualTo(1);
            accept(repository, "b", message("2", "next"));
            assertThat(repository.pendingCount()).isEqualTo(2);
            assertThat(repository.claimNext()).isEmpty();
            repository.failProcessing(1);
            assertThat(claim(repository).text()).isEqualTo("next");
        }
    }

    @Test void confirmedErrorsAreNotModelSuccessAndActualSentTextIsUsedForHistory() {
        try (var store = SqliteStore.open(config())) {
            var repository = repo(store); login(repository);
            accept(repository, "a", message("1", "first"), message("2", "failed"), message("3", "/help"), message("4", "current"));
            Work first = claim(repository);
            repository.saveReply(first.sequence(), ModelStatus.SUCCEEDED, "full model response", "short actual reply");
            Work send = claim(repository);
            assertThat(send.stage()).isEqualTo(Stage.SENDING);
            assertThat(send.clientId()).isNotBlank();
            assertThat(repository.claimNext()).isEmpty();
            repository.finishSend(send.sequence(), Delivery.CONFIRMED);
            Work failed = claim(repository);
            repository.saveReply(failed.sequence(), ModelStatus.FAILED, null, "safe failure notice");
            repository.finishSend(claim(repository).sequence(), Delivery.CONFIRMED);
            Work help = claim(repository);
            repository.saveReply(help.sequence(), ModelStatus.NONE, null, "help text");
            repository.finishSend(claim(repository).sequence(), Delivery.CONFIRMED);
            Work current = claim(repository);
            assertThat(repository.history(current)).extracting(m -> m.text()).containsExactly("first", "short actual reply");
            assertThat(value(store, "SELECT model_status FROM turn WHERE event_sequence=2")).isEqualTo("FAILED");
            assertThat(value(store, "SELECT full_result FROM turn WHERE event_sequence=1")).isEqualTo("full model response");
            assertThatThrownBy(() -> repository.finishSend(first.sequence(), Delivery.CONFIRMED)).hasMessageContaining("INVALID_TRANSITION");
        }
    }

    @ParameterizedTest @EnumSource(value = Stage.class, names = {"RECEIVED", "PROCESSING", "RESPONSE_READY", "SENDING"})
    void recoveryNeverRepeatsUncertainCallsAndPreservesReadyResponse(Stage before) {
        String clientId = null;
        try (var store = SqliteStore.open(config())) {
            var repository = repo(store); login(repository); accept(repository, "a", message("1", "question"));
            if (before != Stage.RECEIVED) claim(repository);
            if (before == Stage.RESPONSE_READY || before == Stage.SENDING) {
                repository.saveReply(1, ModelStatus.SUCCEEDED, "full-generated", "send-me-once");
                clientId = value(store, "SELECT client_id FROM turn WHERE event_sequence=1");
            }
            if (before == Stage.SENDING) claim(repository);
        }
        try (var store = SqliteStore.open(config())) {
            var repository = repo(store);
            assertThat(repository.session().orElseThrow().cursor()).isEqualTo("a");
            if (before == Stage.RECEIVED) {
                assertThat(claim(repository).stage()).isEqualTo(Stage.PROCESSING);
            } else if (before == Stage.RESPONSE_READY) {
                Work send = claim(repository);
                assertThat(send.stage()).isEqualTo(Stage.SENDING);
                assertThat(send.replyText()).isEqualTo("send-me-once");
                assertThat(send.clientId()).isEqualTo(clientId);
                repository.finishSend(send.sequence(), Delivery.CONFIRMED);
                assertThat(repository.claimNext()).isEmpty();
            } else {
                assertThat(repository.claimNext()).isEmpty();
                assertThat(repository.uncertainCount()).isEqualTo(1);
                assertThat(value(store, "SELECT stage FROM turn WHERE event_sequence=1"))
                        .isEqualTo(before == Stage.SENDING ? "DELIVERY_UNKNOWN" : "INTERRUPTED");
            }
        }
    }

    @Test void newConversationSwitchIsAtomicAndDoesNotRollbackWhenSendingFails() {
        String firstConversation;
        String newConversation;
        try (var store = SqliteStore.open(config())) {
            var repository = repo(store); login(repository);
            accept(repository, "a", message("1", "A"), message("2", "/new"), message("3", "B"));
            Work first = claim(repository); firstConversation = first.conversationId();
            assertThat(value(store, "SELECT count(*) FROM turn WHERE conversation_id IS NOT NULL")).isEqualTo("1");
            repository.saveReply(first.sequence(), ModelStatus.SUCCEEDED, "answer A", "answer A");
            repository.finishSend(claim(repository).sequence(), Delivery.CONFIRMED);
            Work command = claim(repository);
            repository.completeNewConversation(command.sequence(), "started new conversation");
            assertThatThrownBy(() -> repository.completeNewConversation(command.sequence(), "duplicate")).hasMessageContaining("INVALID_TRANSITION");
            Work confirmation = claim(repository); newConversation = confirmation.conversationId();
            assertThat(newConversation).isNotEqualTo(firstConversation);
            repository.finishSend(confirmation.sequence(), Delivery.FAILED);
            Work b = claim(repository);
            assertThat(b.conversationId()).isEqualTo(newConversation);
            assertThat(repository.history(b)).isEmpty();
            assertThat(value(store, "SELECT count(*) FROM conversation")).isEqualTo("2");
            assertThat(value(store, "SELECT count(*) FROM turn WHERE stage='SENT'")).isEqualTo("1");
        }
        try (var store = SqliteStore.open(config())) {
            var repository = repo(store);
            accept(repository, "b", message("2", "/new"), message("4", "next"));
            assertThat(claim(repository).conversationId()).isEqualTo(newConversation);
            assertThat(value(store, "SELECT count(*) FROM conversation")).isEqualTo("2");
        }
    }

    @Test void oldGenerationCannotAdvanceCursorSendOrAccessHistory() {
        try (var store = SqliteStore.open(config())) {
            var repository = repo(store); Session old = login(repository);
            accept(repository, "a", message("1", "question"));
            Work running = claim(repository);
            repository.saveReply(running.sequence(), ModelStatus.SUCCEEDED, "full", "answer");
            login(repository);
            assertThat(repository.acceptBatch(old.generation(), "a", "old-response", List.of(message("2", "late")))).isFalse();
            assertThat(repository.invalidateSession(old.generation())).isFalse();
            assertThat(repository.claimNext()).isEmpty();
            assertThat(value(store, "SELECT stage FROM turn WHERE event_sequence=1")).isEqualTo("SEND_FAILED");
            assertThatThrownBy(() -> repository.history(running)).hasMessageContaining("UNAUTHORIZED");
            repository.installSession("different-bot", "https://example.invalid", "new-token", "scanner");
            accept(repository, "new", message("1", "unauthorized-new-bot"));
            assertThat(repository.pendingCount()).isZero();
            assertThat(value(store, "SELECT text FROM inbound_event WHERE bot_id='different-bot'")).isNull();
        }
    }

    @Test void capacityAndDiskPressurePauseWithoutLosingCursorAndCanRecover() {
        var config = new AssistantProperties.Storage(directory.toString(), 1, 100, java.time.Duration.ofSeconds(5));
        var bytes = new AtomicLong(1000);
        try (var store = SqliteStore.open(config)) {
            var repository = new ConversationRepository(store, new Binding("bot", "owner"), config, bytes::get, () -> { });
            login(repository);
            assertThatThrownBy(() -> accept(repository, "too-large", message("1", "a"), message("2", "b"))).hasMessageContaining("BACKLOG");
            assertThat(repository.pendingCount()).isZero();
            assertThat(repository.session().orElseThrow().cursor()).isEmpty();
            accept(repository, "a", message("1", "a"));
            assertThat(repository.intakeState()).isEqualTo(Intake.BACKLOG);
            assertThatThrownBy(() -> accept(repository, "b", message("2", "b"))).hasMessageContaining("BACKLOG");
            repository.failProcessing(claim(repository).sequence());
            assertThat(repository.intakeState()).isEqualTo(Intake.READY);
            bytes.set(10);
            assertThat(repository.intakeState()).isEqualTo(Intake.LOW_DISK);
            assertThatThrownBy(() -> accept(repository, "b", message("2", "b"))).hasMessageContaining("LOW_DISK");
            assertThat(repository.session().orElseThrow().cursor()).isEqualTo("a");
            bytes.set(1000);
            accept(repository, "b", message("2", "b"));
            assertThat(repository.pendingCount()).isEqualTo(1);
        }
    }

    @Test void unboundMessagesAreIgnoredAndSensitiveRecordStringsAreSafe() {
        try (var store = SqliteStore.open(config())) {
            var repository = new ConversationRepository(store, new Binding("", ""), config());
            Session session = login(repository);
            Inbound message = message("1", "private-message");
            accept(repository, "a", message);
            assertThat(repository.claimNext()).isEmpty();
            assertThat(repository.pendingCount()).isZero();
            assertThat(value(store, "SELECT text FROM inbound_event")).isNull();
            assertThat(session.toString()).doesNotContain("synthetic-token", "scanner", "example.invalid");
            assertThat(message.toString()).doesNotContain("private-message", "synthetic-context", "owner");
        }
    }
}