package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.model.ModelConfiguration;

import io.github.wochen5770.talkweave.channel.wechat.ScannerIdentityResolver.VerifiedIdentity;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import io.github.wochen5770.talkweave.model.TokenUsage;
import io.github.wochen5770.talkweave.support.TestProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedUsers.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

class ManagedRepositoryIT {
    @TempDir Path directory;
    private final io.github.wochen5770.talkweave.managed.persistence.ExternalBusinessFixture database = new io.github.wochen5770.talkweave.managed.persistence.ExternalBusinessFixture();
    @org.junit.jupiter.api.AfterEach void cleanSyntheticRows() { database.close(); }
    private final MutableClock clock = new MutableClock();

    @Test void botAndGlobalAccountUniquenessAreBothEnforced() {
        try (var f = fixture()) {
            var a = f.newBound("a");
            var b = f.users.create("b");
            var attempt = f.verifying(b.id(), Mode.INITIAL);
            expect(CONFLICT, () -> f.users.activate(b.id(), attempt.id(), identity("other", "a"), credentials("a")));
            expect(CONFLICT, () -> f.users.activate(b.id(), attempt.id(), identity("a", "other"), credentials("other")));
            assertThat(f.users.scope(a.userId())).isEqualTo(a);
            expect(UNAUTHORIZED, () -> f.users.scope(b.id()));
        }
    }

    @Test void concurrentFinalizationCannotAssignTheSameAccountTwice() throws Exception {
        try (var f = fixture(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = f.users.create("a"); var b = f.users.create("b");
            var aa = f.verifying(a.id(), Mode.INITIAL); var bb = f.verifying(b.id(), Mode.INITIAL);
            var start = new CountDownLatch(1);
            var outcomes = pool.invokeAll(List.<Callable<Boolean>>of(
                    () -> { start.countDown(); start.await(); return activate(f, a.id(), aa.id()); },
                    () -> { start.await(); return activate(f, b.id(), bb.id()); }));
            assertThat(outcomes.stream().map(future -> { try { return future.get(); } catch (Exception e) { throw new AssertionError(e); } }).toList())
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test void refreshExpirationAndDisableInvalidateAttempts() {
        try (var f = fixture()) {
            var a = f.users.create("a"); var first = f.verifying(a.id(), Mode.INITIAL);
            var second = f.verifying(a.id(), Mode.INITIAL);
            expect(CONFLICT, () -> f.users.activate(a.id(), first.id(), identity("a", "a"), credentials("a")));
            clock.advance(8);
            expect(EXPIRED, () -> f.users.activate(a.id(), second.id(), identity("a", "a"), credentials("a")));
            var third = f.verifying(a.id(), Mode.INITIAL);
            f.users.setEnabled(a.id(), false);
            expect(UNAUTHORIZED, () -> f.users.activate(a.id(), third.id(), identity("a", "a"), credentials("a")));
            assertThat(f.users.attempt(a.id(), third.id()).phase()).isEqualTo(Phase.CANCELLED);
        }
    }

    @Test void reauthenticationKeepsBindingButRejectsAnotherOwnerAndOldEpochWork() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var b = f.newBound("b");
            var original = f.accept(a, "first", "private-a"); f.deliver(a, "answer-a");
            var pending = f.accept(a, "pending", "still-a");
            assertThat(f.chat.claim(a)).isPresent();
            f.users.setEnabled(a.userId(), false); f.users.setEnabled(a.userId(), true);
            var task = f.verifying(a.userId(), Mode.REAUTHENTICATE);
            expect(UNAUTHORIZED, () -> f.users.activate(a.userId(), task.id(), identity("b", "b"), credentials("b")));
            var restored = f.users.activate(a.userId(), task.id(), identity("a", "a"), credentials("a"));
            assertThat(restored.bindingId()).isEqualTo(a.bindingId());
            assertThat(f.chat.accept(restored, updates(message("first", restored.senderId(), "replayed old login message")))).isEmpty();
            expect(UNAUTHORIZED, () -> f.chat.saveReply(a, pending.sequence(), "late", true));
            var next = f.accept(restored, "next", "continue");
            assertThat(next.conversationId()).isEqualTo(original.conversationId());
            assertThat(f.chat.history(restored, next.sequence())).extracting("text").containsExactly("private-a", "answer-a");
            assertThat(f.users.scope(b.userId())).isEqualTo(b);
        }
    }

    @Test void replacementNeverCarriesOldHistoryIntoNewBinding() {
        try (var f = fixture()) {
            var old = f.newBound("a"); f.accept(old, "first", "old-secret"); f.deliver(old, "old-answer");
            var task = f.verifying(old.userId(), Mode.REPLACE);
            expect(UNAUTHORIZED, () -> f.users.connection(old));
            var replacement = f.users.activate(old.userId(), task.id(), identity("new-owner", "new-bot"), credentials("new-bot"));
            var next = f.accept(replacement, "next", "hello");
            assertThat(replacement.bindingId()).isNotEqualTo(old.bindingId());
            assertThat(f.chat.history(replacement, next.sequence())).isEmpty();
        }
    }

    @Test void sameAccountWithNewBotPreservesHistoryResetsCursorAndRevokesOldWork() {
        try (var f = fixture()) {
            var old = f.newBound("a"); var b = f.newBound("b");
            var first = f.accept(old, "one", "keep-history"); f.deliver(old, "keep-answer");
            f.chat.accept(old, new WechatApiClient.Updates(List.of(), "old-bot-cursor", null));
            var pending = f.accept(old, "pending", "old-work"); f.chat.claim(old).orElseThrow();
            var task = f.verifying(old.userId(), Mode.REAUTHENTICATE);
            expect(CONFLICT, () -> f.users.activate(old.userId(), task.id(), identity("a", "b"), credentials("b")));
            assertThat(f.users.scope(old.userId())).isEqualTo(old);
            var renewed = f.users.activate(old.userId(), task.id(), identity("a", "new-a"), credentials("new-a"));
            assertThat(renewed.bindingId()).isEqualTo(old.bindingId());
            assertThat(renewed.botId()).isNotEqualTo(old.botId());
            assertThat(renewed.generation()).isEqualTo(old.generation() + 1);
            assertThat(f.users.connection(renewed).cursor()).isEmpty();
            expect(UNAUTHORIZED, () -> f.users.connection(old));
            expect(UNAUTHORIZED, () -> f.chat.saveReply(old, pending.sequence(), "late", true));
            var next = f.accept(renewed, "one", "new-bot-message-id-can-repeat");
            assertThat(next.conversationId()).isEqualTo(first.conversationId());
            assertThat(f.chat.history(renewed, next.sequence())).extracting("text").containsExactly("keep-history", "keep-answer");
            assertThat(f.users.scope(b.userId())).isEqualTo(b);
        }
    }

    @Test void historiesScopesAndDuplicateMessageIdsAreIsolated() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var b = f.newBound("b");
            var first = f.accept(a, "same-id", "a-only"); f.deliver(a, "a-answer");
            f.accept(b, "same-id", "b-only"); f.deliver(b, "b-answer");
            var an = f.accept(a, "second", "recall"); var bn = f.accept(b, "second", "recall");
            assertThat(f.chat.history(a, an.sequence())).extracting("text").containsExactly("a-only", "a-answer");
            assertThat(f.chat.history(b, bn.sequence())).extracting("text").containsExactly("b-only", "b-answer");
            expect(NOT_FOUND, () -> f.chat.history(b, first.sequence()));
            var forged = new ManagedScope(a.userId(), b.bindingId(), b.botId(), b.senderId(), b.generation(), a.authEpoch());
            expect(UNAUTHORIZED, () -> f.users.connection(forged));
            assertThat(f.chat.accept(a, updates(message("unknown", b.senderId(), "unauthorized")))).isEmpty();
            assertThat(f.chat.accept(a, updates(message("same-id", a.senderId(), "different replay body")))).isEmpty();
        }
    }

    @Test void idleWindowSlidesAndExpiresAtExactBoundaryWithoutHelpOrDuplicatesRefreshingIt() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var one = f.accept(a, "one", "first");
            clock.advance(20); var two = f.accept(a, "two", "second");
            assertThat(two.conversationId()).isEqualTo(one.conversationId());
            clock.advance(29); f.accept(a, "help", "/help");
            f.chat.accept(a, updates(message("one", a.senderId(), "duplicate")));
            clock.advance(1); var three = f.accept(a, "three", "new session");
            assertThat(three.conversationId()).isNotEqualTo(one.conversationId());
            assertThat(f.users.scope(a.userId())).isEqualTo(a);
        }
    }

    @Test void newCommandAndQueueDelayDoNotReassignAlreadyAcceptedMessages() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var first = f.accept(a, "first", "old-context");
            clock.advance(1); var second = f.accept(a, "second", "same-context");
            var reset = f.accept(a, "new", "/new"); var after = f.accept(a, "after", "fresh");
            clock.advance(60);
            assertThat(second.conversationId()).isEqualTo(first.conversationId());
            assertThat(after.conversationId()).isEqualTo(reset.conversationId()).isNotEqualTo(first.conversationId());
            assertThat(f.chat.claim(a).orElseThrow().event().conversationId()).isEqualTo(first.conversationId());
            assertThat(f.chat.history(a, after.sequence())).isEmpty();
        }
    }

    @Test void timeoutUpdateIsImmediateAndSurvivesRestart() {
        ManagedScope scope; String oldConversation;
        try (var f = fixture()) {
            scope = f.newBound("a"); oldConversation = f.accept(scope, "first", "first").conversationId();
            f.settings.setIdleMinutes(10);
            expect(INVALID_INPUT, () -> f.settings.setIdleMinutes(0));
            expect(INVALID_INPUT, () -> f.settings.setIdleMinutes(1441));
        }
        clock.advance(15);
        try (var f = fixture()) {
            assertThat(f.settings.settings().idleMinutes()).isEqualTo(10);
            var next = f.accept(scope, "after-restart", "new");
            assertThat(next.conversationId()).isNotEqualTo(oldConversation);
            f.settings.setIdleMinutes(1440);
            assertThat(f.accept(scope, "continue", "continue").conversationId()).isEqualTo(next.conversationId());
        }
    }

    @Test void backlogRollsBackEventsConversationsAndCursorAsOneBatch() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var limited = new ManagedConversations(f.store, clock, 1, 2);
            expect(BACKLOG, () -> limited.accept(a, new WechatApiClient.Updates(List.of(message("one", a.senderId(), "one"), message("two", a.senderId(), "two")), "new-cursor", null)));
            assertThat(f.users.connection(a).cursor()).isEmpty();
            assertThat(limited.accept(a, updates(message("one", a.senderId(), "one")))).hasSize(1);
        }
    }

    @Test void modelSettingsAreVersionedAndBootstrapCannotResetAnAdministrator() {
        try (var f = fixture()) {
            assertThat(f.settings.currentModel()).isEmpty();
            assertThat(f.settings.initializeAdministrator("admin", "fake-prehashed-password")).isTrue();
            assertThat(f.settings.initializeAdministrator("attacker", "other-hash")).isFalse();
            assertThat(f.settings.administrator().orElseThrow().username()).isEqualTo("admin");
            var first = f.settings.saveModel(TestProperties.model("https://first.invalid", "fake-first-key"));
            var second = f.settings.saveModel(TestProperties.model("https://second.invalid", "fake-second-key"));
            assertThat(second.version()).isGreaterThan(first.version());
            assertThat(first.configuration().apiKey()).isEqualTo("fake-first-key");
            assertThat(f.settings.currentModel().orElseThrow().configuration().apiKey()).isEqualTo("fake-second-key");
            assertThat(first.toString()).doesNotContain("fake-first-key");
        }
    }

    @Test void usageIsIdempotentAttributedToOriginalCallAndMissingIsNotZero() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var b = f.newBound("b");
            long version = f.settings.saveModel(TestProperties.model("https://model.invalid", "fake-key")).version();
            var event = f.accept(a, "one", "hello"); f.chat.claim(a).orElseThrow();
            String attempt = f.usage.begin(a, event.sequence(), version);
            f.users.setEnabled(a.userId(), false);
            f.usage.record(attempt, ManagedUsage.Outcome.SUCCEEDED, TokenUsage.normalize(2000, 100, 1500));
            f.usage.record(attempt, ManagedUsage.Outcome.SUCCEEDED, TokenUsage.normalize(9999, 9999, 9999));
            var summary = f.usage.summary(a.userId(), event.conversationId(), 0, Long.MAX_VALUE);
            assertThat(summary.attempts()).isEqualTo(1);
            assertThat(summary.knownInputTokens()).isEqualTo(2000);
            assertThat(summary.knownCachedInputTokens()).isEqualTo(1500);
            assertThat(summary.coveredCacheHitRatio()).isEqualTo(0.75);
            assertThat(f.usage.summary(b.userId(), null, 0, Long.MAX_VALUE).knownInputTokens()).isNull();
            expect(NOT_FOUND, () -> f.usage.summary(b.userId(), event.conversationId(), 0, Long.MAX_VALUE));
        }
    }

    @Test void partialAndUnreturnedUsageRemainVisibleAcrossRestart() {
        String userId;
        try (var f = fixture()) {
            var a = f.newBound("a"); userId = a.userId();
            long version = f.settings.saveModel(TestProperties.model("https://model.invalid", "fake-key")).version();
            var first = f.accept(a, "one", "one"); f.chat.claim(a).orElseThrow();
            String id = f.usage.begin(a, first.sequence(), version);
            f.usage.record(id, ManagedUsage.Outcome.SUCCEEDED, TokenUsage.normalize(200, 10, null));
            f.chat.saveReply(a, first.sequence(), "answer", true); f.chat.claim(a).orElseThrow(); f.chat.finishSend(a, first.sequence(), ManagedConversations.Delivery.CONFIRMED);
            var second = f.accept(a, "two", "two"); f.chat.claim(a).orElseThrow(); f.usage.begin(a, second.sequence(), version);
        }
        try (var f = fixture()) {
            var summary = f.usage.summary(userId, null, 0, Long.MAX_VALUE);
            assertThat(summary.attempts()).isEqualTo(2);
            assertThat(summary.unreportedAttempts()).isEqualTo(1);
            assertThat(summary.incompleteAttempts()).isEqualTo(2);
            assertThat(summary.knownInputTokens()).isEqualTo(200);
            assertThat(summary.knownCachedInputTokens()).isNull();
            assertThat(summary.coveredCacheHitRatio()).isNull();
        }
    }

    @Test void simultaneousMessagesAfterTimeoutShareOneNewConversation() throws Exception {
        try (var f = fixture(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = f.newBound("a"); String old = f.accept(a, "first", "first").conversationId(); clock.advance(30);
            var work = pool.invokeAll(List.<Callable<ManagedConversations.Event>>of(
                    () -> f.accept(a, "concurrent-one", "one"), () -> f.accept(a, "concurrent-two", "two")));
            assertThat(work.get(0).get().conversationId()).isEqualTo(work.get(1).get().conversationId()).isNotEqualTo(old);
        }
    }

    @Test void failedModelResponseDoesNotRefreshActivityAndExpiredHistoryIsNotLoaded() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var first = f.accept(a, "first", "private-first"); f.deliver(a, "private-answer");
            clock.advance(1); var failed = f.accept(a, "failed", "failed question"); f.chat.claim(a).orElseThrow();
            clock.advance(29); f.chat.saveReply(a, failed.sequence(), "model unavailable", false);
            f.chat.claim(a).orElseThrow(); f.chat.finishSend(a, failed.sequence(), ManagedConversations.Delivery.CONFIRMED);
            clock.advance(1); var next = f.accept(a, "next", "new context");
            assertThat(next.conversationId()).isNotEqualTo(first.conversationId());
            assertThat(f.chat.history(a, next.sequence())).isEmpty();
        }
    }

    @Test void duplicateActivationIsIdempotentAndInterruptedProcessingIsNotReplayed() {
        ManagedScope scope;
        try (var f = fixture()) {
            String user = f.users.create("a").id(); var attempt = f.verifying(user, Mode.INITIAL);
            scope = f.users.activate(user, attempt.id(), identity("a", "a"), credentials("a"));
            assertThat(f.users.activate(user, attempt.id(), identity("a", "a"), credentials("a"))).isEqualTo(scope);
            f.accept(scope, "first", "may have been charged"); f.chat.claim(scope).orElseThrow();
        }
        try (var f = fixture()) {
            assertThat(f.chat.claim(scope)).isEmpty();
        }
    }
    @Test void modelHttpAttemptsReachScopedDurableUsageEvenWhenDeliveryFails() throws Exception {
        try (var f = fixture(); var http = new io.github.wochen5770.talkweave.support.FakeHttpService()) {
            var scope = f.newBound("a"); var other = f.newBound("b");
            var saved = f.settings.saveModel(TestProperties.model(http.baseUri().toString(), "fake-key"));
            var event = f.accept(scope, "model", "hello"); f.chat.claim(scope).orElseThrow();
            var context = new io.github.wochen5770.talkweave.model.ModelRequestContext(scope.userId(), scope.bindingId(), event.conversationId(),
                    event.sequence(), scope.generation(), scope.authEpoch(), saved.version());
            http.enqueue(429, "{}", Map.of("Retry-After", "0"));
            http.enqueue(200, "{\"id\":\"fake\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"answer\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":60}}}");
            try (var client = new io.github.wochen5770.talkweave.model.CompatibleChatClient(saved.configuration(), saved.version())) {
                var prompt = new io.github.wochen5770.talkweave.conversation.ContextBudget(saved.configuration())
                        .prepare(f.chat.history(scope, event.sequence()), event.text()).orElseThrow();
                var reply = client.answer(context, prompt, new ManagedModelObserver(f.usage, scope, context));
                f.chat.saveReply(scope, event.sequence(), reply.text(), true);
                f.chat.claim(scope).orElseThrow();
                f.chat.finishSend(scope, event.sequence(), ManagedConversations.Delivery.FAILED);
            }
            var summary = f.usage.summary(scope.userId(), event.conversationId(), 0, Long.MAX_VALUE);
            assertThat(summary.attempts()).isEqualTo(2); assertThat(summary.unreportedAttempts()).isEqualTo(1);
            assertThat(summary.knownInputTokens()).isEqualTo(80); assertThat(summary.knownOutputTokens()).isEqualTo(5);
            assertThat(summary.knownCachedInputTokens()).isEqualTo(60); assertThat(summary.coveredCacheHitRatio()).isEqualTo(0.75);
            assertThat(f.usage.summary(other.userId(), null, 0, Long.MAX_VALUE).attempts()).isZero();
            assertThat(http.take().body()).isEqualTo(http.take().body());
            assertThat(http.pendingRequests()).isZero();
        }
    }
    @Test void persistedObserverRejectsConversationInjectionAndRechecksRevocationBetweenAttempts() {
        try (var f = fixture()) {
            var scope = f.newBound("a");
            long version = f.settings.saveModel(TestProperties.model("https://model.invalid", "fake-key")).version();
            var event = f.accept(scope, "one", "one"); f.chat.claim(scope).orElseThrow();
            var wrong = new io.github.wochen5770.talkweave.model.ModelRequestContext(scope.userId(), scope.bindingId(), "another-conversation",
                    event.sequence(), scope.generation(), scope.authEpoch(), version);
            expect(UNAUTHORIZED, () -> new ManagedModelObserver(f.usage, scope, wrong).beforeAttempt(1));
            var correct = new io.github.wochen5770.talkweave.model.ModelRequestContext(scope.userId(), scope.bindingId(), event.conversationId(),
                    event.sequence(), scope.generation(), scope.authEpoch(), version);
            var observer = new ManagedModelObserver(f.usage, scope, correct);
            observer.beforeAttempt(1);
            f.users.setEnabled(scope.userId(), false); f.users.setEnabled(scope.userId(), true);
            f.settings.saveModel(TestProperties.model("https://new-model.invalid", "fake-new-key"));
            observer.afterAttempt(1, io.github.wochen5770.talkweave.model.ModelAttemptObserver.Outcome.FAILED, TokenUsage.unknown());
            expect(UNAUTHORIZED, () -> observer.beforeAttempt(2));
            assertThat(f.usage.summary(scope.userId(), event.conversationId(), 0, Long.MAX_VALUE).attempts()).isEqualTo(1);
            long recordedVersion = f.store.transaction(c -> Sql.scalar(c, "SELECT model_version FROM model_attempt"));
            assertThat(recordedVersion).isEqualTo(version);
        }
    }

    @Test void turnWorkerSendsOnlyOwnConfirmedHistoryAndNeverRepeatsModelForSend() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var b = f.newBound("b");
            f.settings.saveModel(TestProperties.model("https://model.invalid", "fake-key"));
            var prompts = new ArrayList<List<io.github.wochen5770.talkweave.conversation.DialogueMessage>>();
            var sends = new ArrayList<String>();
            var model = (io.github.wochen5770.talkweave.managed.runtime.ManagedTurnWorker.ModelCall) (snapshot, context, messages, observer) -> {
                observer.beforeAttempt(1); prompts.add(messages);
                observer.afterAttempt(1, io.github.wochen5770.talkweave.model.ModelAttemptObserver.Outcome.SUCCEEDED, TokenUsage.normalize(10, 2, null));
                return new io.github.wochen5770.talkweave.assistant.AssistantService.Reply("reply-" + context.userId(), false);
            };
            var worker = new io.github.wochen5770.talkweave.managed.runtime.ManagedTurnWorker(f.users, f.settings, f.chat, f.usage, model,
                    (credentials, recipient, token, clientId, text) -> sends.add(recipient + ":" + text), 4096);
            f.accept(a, "first", "private-a"); f.accept(b, "first", "private-b");
            assertThat(worker.runOnce(a)).isTrue(); assertThat(worker.runOnce(b)).isTrue();
            assertThat(worker.runOnce(a)).isTrue(); assertThat(worker.runOnce(b)).isTrue();
            assertThat(worker.runOnce(a)).isFalse();
            f.accept(a, "second", "continue-a"); assertThat(worker.runOnce(a)).isTrue();
            assertThat(prompts).hasSize(3);
            assertThat(prompts.get(2)).extracting("text").contains("private-a", "reply-" + a.userId(), "continue-a").doesNotContain("private-b");
            assertThat(sends).containsExactly(a.senderId() + ":reply-" + a.userId(), b.senderId() + ":reply-" + b.userId());
            assertThat(f.usage.summary(a.userId(), null, 0, Long.MAX_VALUE).attempts()).isEqualTo(2);
        }
    }
    @Test void turnWorkerDoesNotDispatchLateReplyAfterDisableAndPreservesAccounting() {
        try (var f = fixture()) {
            var a = f.newBound("a"); f.settings.saveModel(TestProperties.model("https://model.invalid", "fake-key"));
            var event = f.accept(a, "first", "question");
            var worker = new io.github.wochen5770.talkweave.managed.runtime.ManagedTurnWorker(f.users, f.settings, f.chat, f.usage,
                    (snapshot, context, messages, observer) -> {
                        observer.beforeAttempt(1); f.users.setEnabled(a.userId(), false);
                        observer.afterAttempt(1, io.github.wochen5770.talkweave.model.ModelAttemptObserver.Outcome.SUCCEEDED, TokenUsage.normalize(12, 3, 5));
                        return new io.github.wochen5770.talkweave.assistant.AssistantService.Reply("late", false);
                    }, (credentials, recipient, token, clientId, text) -> { throw new AssertionError("Must not send"); }, 4096);
            expect(UNAUTHORIZED, () -> worker.runOnce(a));
            assertThat(f.usage.summary(a.userId(), event.conversationId(), 0, Long.MAX_VALUE).knownInputTokens()).isEqualTo(12);
        }
    }
    @Test void turnWorkerKeepsLocalCommandsFreeAndDoesNotRetryAmbiguousSends() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var count = new java.util.concurrent.atomic.AtomicInteger();
            var worker = new io.github.wochen5770.talkweave.managed.runtime.ManagedTurnWorker(f.users, f.settings, f.chat, f.usage,
                    (snapshot, context, messages, observer) -> { throw new AssertionError("No model configured"); },
                    (credentials, recipient, token, clientId, text) -> { count.incrementAndGet(); throw new io.github.wochen5770.talkweave.runtime.RemoteFailure(
                            io.github.wochen5770.talkweave.runtime.RemoteFailure.Source.WECHAT, io.github.wochen5770.talkweave.runtime.RemoteFailure.Kind.TIMEOUT); }, 4096);
            f.accept(a, "one", "/new"); f.accept(a, "two", "model missing");
            for (int i = 0; i < 4; i++) assertThat(worker.runOnce(a)).isTrue();
            assertThat(worker.runOnce(a)).isFalse(); assertThat(count.get()).isEqualTo(2);
            assertThat(f.usage.summary(a.userId(), null, 0, Long.MAX_VALUE).attempts()).isZero();
        }
    }

    @Test void queuedTurnUsesAssemblyTimeAndNeverPersistsDynamicSystemText() {
        clock.now = Instant.parse("2026-09-29T15:59:00Z");
        try (var f = fixture()) {
            var scope = f.newBound("time");
            var config = f.settings.saveModel(TestProperties.model("https://model.invalid", "fake-key"));
            var first = f.accept(scope, "first", "今天是什么日期？");
            clock.advance(2); // The already-assigned conversation waits across midnight.
            var prompts = new ArrayList<List<io.github.wochen5770.talkweave.conversation.DialogueMessage>>();
            var worker = new io.github.wochen5770.talkweave.managed.runtime.ManagedTurnWorker(f.users, f.settings, f.chat, f.usage,
                    (snapshot, context, messages, observer) -> {
                        prompts.add(messages);
                        assertThat(context.conversationId()).isEqualTo(first.conversationId());
                        observer.beforeAttempt(1);
                        observer.afterAttempt(1, io.github.wochen5770.talkweave.model.ModelAttemptObserver.Outcome.SUCCEEDED, TokenUsage.unknown());
                        return new io.github.wochen5770.talkweave.assistant.AssistantService.Reply("原始回复", false);
                    }, (credentials, recipient, token, clientId, text) -> { }, 4096, event -> () -> { },
                    new io.github.wochen5770.talkweave.conversation.ConversationTimeContext(clock, "Asia/Shanghai"));
            assertThat(worker.runOnce(scope)).isTrue();
            assertThat(worker.runOnce(scope)).isTrue();
            var prompt = prompts.getFirst();
            assertThat(prompt).extracting("role").containsExactly(
                    io.github.wochen5770.talkweave.conversation.DialogueMessage.Role.SYSTEM,
                    io.github.wochen5770.talkweave.conversation.DialogueMessage.Role.SYSTEM,
                    io.github.wochen5770.talkweave.conversation.DialogueMessage.Role.USER);
            assertThat(prompt.get(1).text()).contains("当前时间（本轮请求组装时）：2026-09-30 00:01:00", "消息接收时间：2026-09-29 23:59:00");
            assertThat(prompt.getLast().text()).isEqualTo(first.text());
            clock.advance(1);
            var second = f.accept(scope, "second", "现在呢？");
            assertThat(second.conversationId()).isEqualTo(first.conversationId());
            assertThat(f.chat.history(scope, second.sequence())).extracting("text").containsExactly("今天是什么日期？", "原始回复");
            assertThat(worker.runOnce(scope)).isTrue();
            assertThat(prompts.get(1).get(3).text()).contains("2026-09-30 00:02:00");
            assertThat(f.settings.currentModel().orElseThrow()).isEqualTo(config);
            String original = f.store.transaction(c -> {
                try (var s = Sql.prepare(c, "SELECT text FROM inbound_event WHERE sequence=?", first.sequence()); var r = s.executeQuery()) {
                    r.next(); return r.getString(1);
                }
            });
            assertThat(original).isEqualTo(first.text());
        }
    }

    @Test void timeBudgetOverflowStaysLocalWithoutModelAttempt() {
        try (var f = fixture()) {
            var scope = f.newBound("budget");
            var c = TestProperties.model("https://model.invalid", "fake-key");
            f.settings.saveModel(new ModelConfiguration(c.apiBaseUrl(), c.apiKey(), c.name(), "system", false,
                    256, 100, 10, 0, c.requestTimeout(), c.totalTimeBudget(), 0));
            var event = f.accept(scope, "one", "now");
            var sends = new ArrayList<String>();
            var worker = new io.github.wochen5770.talkweave.managed.runtime.ManagedTurnWorker(f.users, f.settings, f.chat, f.usage,
                    (snapshot, context, messages, observer) -> { throw new AssertionError("Budget overflow must not dispatch"); },
                    (credentials, recipient, token, clientId, text) -> sends.add(text), 4096, ignored -> () -> { },
                    new io.github.wochen5770.talkweave.conversation.ConversationTimeContext(clock, "Asia/Shanghai"));
            assertThat(worker.runOnce(scope)).isTrue();
            assertThat(worker.runOnce(scope)).isTrue();
            assertThat(sends).singleElement().asString().contains("输入超出上下文预算");
            assertThat(f.usage.summary(scope.userId(), event.conversationId(), 0, Long.MAX_VALUE).attempts()).isZero();
            var next = f.accept(scope, "two", "next");
            assertThat(f.chat.history(scope, next.sequence())).isEmpty();
        }
    }

    @Test void administratorConversationMetadataKeepsHistoricalBindingsSeparateAndPagesTiedTimes() {
        try (var f = fixture()) {
            var a = f.newBound("a"); var b = f.newBound("b");
            var old = f.accept(a, "a1", "private-old"); f.deliver(a, "private-answer");
            f.accept(b, "b1", "other-private"); f.deliver(b, "other-answer");
            var task = f.verifying(a.userId(), Mode.REPLACE);
            var replacement = f.users.activate(a.userId(), task.id(), identity("next", "next"), credentials("next"));
            var next = f.accept(replacement, "a2", "private-new"); f.deliver(replacement, "private-answer-new");
            var first = f.usage.conversations(a.userId(), Long.MAX_VALUE, 1);
            assertThat(first).hasSize(1); assertThat(first.getFirst().id()).isEqualTo(next.conversationId());
            assertThat(first.getFirst().bindingVersion()).isEqualTo(2); assertThat(first.getFirst().bindingCurrent()).isTrue();
            var second = f.usage.conversations(a.userId(), first.getFirst().sequence(), 1);
            assertThat(second).hasSize(1); assertThat(second.getFirst().id()).isEqualTo(old.conversationId());
            assertThat(second.getFirst().bindingVersion()).isEqualTo(1); assertThat(second.getFirst().bindingCurrent()).isFalse();
            assertThat(first.getFirst().createdAt()).isEqualTo(second.getFirst().createdAt());
            assertThat(f.users.overview(a.userId()).id()).isEqualTo(a.userId());
            expect(NOT_FOUND, () -> f.users.overview("missing"));
            expect(NOT_FOUND, () -> f.usage.summary(b.userId(), old.conversationId(), 0, Long.MAX_VALUE));
            // Admin accounting metadata remains accessible without granting old history to the new identity.
            assertThat(f.usage.summary(a.userId(), old.conversationId(), 0, Long.MAX_VALUE).attempts()).isZero();
        }
    }

    private Fixture fixture() { return new Fixture(); }
    private class Fixture implements AutoCloseable {
        final ManagedStore store = database.open();
        final ManagedUsers users = new ManagedUsers(store, clock);
        final ManagedSettings settings = new ManagedSettings(store, clock);
        final ManagedConversations chat = new ManagedConversations(store, clock);
        final ManagedUsage usage = new ManagedUsage(store, clock);
        ManagedScope newBound(String name) {
            String id = users.create(name).id(); var task = verifying(id, Mode.INITIAL);
            return users.activate(id, task.id(), identity(name, name), credentials(name));
        }
        Attempt verifying(String userId, Mode mode) {
            var task = users.begin(userId, mode);
            return users.advance(userId, task.id(), Phase.REQUESTING_QR, Phase.VERIFYING_IDENTITY);
        }
        ManagedConversations.Event accept(ManagedScope scope, String id, String text) { return chat.accept(scope, updates(message(id, scope.senderId(), text))).getFirst(); }
        void deliver(ManagedScope scope, String answer) {
            var work = chat.claim(scope).orElseThrow();
            chat.saveReply(scope, work.event().sequence(), answer, true);
            assertThat(chat.claim(scope).orElseThrow().sending()).isTrue();
            chat.finishSend(scope, work.event().sequence(), ManagedConversations.Delivery.CONFIRMED);
        }
        @Override public void close() { store.close(); }
    }
    private static boolean activate(Fixture f, String user, String task) {
        try { f.users.activate(user, task, identity("shared", "shared"), credentials("shared")); return true; }
        catch (ManagedProblem e) { assertThat(e.code()).isEqualTo(CONFLICT); return false; }
    }
    private static VerifiedIdentity identity(String account, String bot) {
        return new VerifiedIdentity("test-only-global", "fake-account-" + account, "fake-bot-" + bot, "fake-sender-" + account,
                URI.create("https://ilinkai.weixin.qq.com"), "synthetic-test-not-production");
    }
    private static WechatApiClient.Credentials credentials(String bot) {
        return new WechatApiClient.Credentials("fake-bot-" + bot, "fake-token-" + bot, URI.create("https://ilinkai.weixin.qq.com"), "fake-scanner-" + bot);
    }
    private static WechatApiClient.Incoming message(String id, String sender, String text) { return new WechatApiClient.Incoming(id, sender, "fake-context", text, false, 1, 2); }
    private static WechatApiClient.Updates updates(WechatApiClient.Incoming... messages) { return new WechatApiClient.Updates(List.of(messages), "fake-cursor", null); }
    private static void expect(ManagedProblem.Code code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ManagedProblem.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T02:00:00Z");
        void advance(long minutes) { now = now.plusSeconds(minutes * 60); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
