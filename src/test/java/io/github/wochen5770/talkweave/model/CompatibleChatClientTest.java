package io.github.wochen5770.talkweave.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import io.github.wochen5770.talkweave.support.FakeHttpService;
import io.github.wochen5770.talkweave.support.TestProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.wochen5770.talkweave.conversation.DialogueMessage.Role.*;
import static org.assertj.core.api.Assertions.*;

class CompatibleChatClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final java.util.List<CompatibleChatClient> clients = new java.util.ArrayList<>();
    private CompatibleChatClient create(io.github.wochen5770.talkweave.runtime.AssistantProperties.Model config) {
        var client = new CompatibleChatClient(config);
        clients.add(client);
        return client;
    }
    @org.junit.jupiter.api.AfterEach void closeClients() { clients.forEach(CompatibleChatClient::close); }
    private static List<DialogueMessage> conversation() {
        return List.of(new DialogueMessage(SYSTEM, "test system"), new DialogueMessage(USER, "question one"),
                new DialogueMessage(ASSISTANT, "answer one"), new DialogueMessage(USER, "question two"));
    }
    private static String completion(String content, String finish) {
        return "{\"id\":\"fake-response\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"custom-test-model\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"" + finish + "\",\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    }

    @Test void preservesPrefixIdentityRolesAndMinimalFields() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(200, completion("synthetic answer", "stop"));
            var client = create(TestProperties.model(fake.baseUri() + "/gateway", "fake-key"));
            assertThat(client.answer(conversation()).text()).isEqualTo("synthetic answer");
            var request = fake.take();
            assertThat(request.uri().getPath()).isEqualTo("/gateway/v1/chat/completions");
            assertThat(request.headers().get("Authorization")).containsExactly("Bearer fake-key");
            var body = json.readTree(request.body());
            assertThat(body.properties().stream().map(Map.Entry::getKey)).containsExactlyInAnyOrder("model", "messages", "stream", "max_tokens");
            assertThat(body.path("model").asText()).isEqualTo("custom-test-model");
            assertThat(body.path("stream").asBoolean()).isFalse();
            assertThat(body.path("max_tokens").asInt()).isEqualTo(1024);
            assertThat(body.path("messages").findValuesAsText("role")).containsExactly("system", "user", "assistant", "user");
            assertThat(fake.pendingRequests()).isZero();
        }
    }

    @Test void marksLengthTerminationWithoutInventingSuccess() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(200, completion("partial", "length"));
            var reply = create(TestProperties.model(fake.baseUri().toString(), "fake-key")).answer(conversation());
            assertThat(reply.truncated()).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(ints = {302, 400, 401, 403, 500, 503})
    void errorsDoNotRetryOrLeakBodies(int status) throws Exception {
        try (var fake = new FakeHttpService(); var other = new FakeHttpService()) {
            fake.enqueue(status, "{\"error\":\"fake-secret-provider-body\"}", Map.of("Location", other.baseUri().toString()));
            var client = create(TestProperties.model(fake.baseUri().toString(), "fake-key"));
            assertThatThrownBy(() -> client.answer(conversation())).isInstanceOf(RemoteFailure.class)
                    .hasMessageNotContaining("fake-secret").hasMessageNotContaining("fake-key");
            fake.take();
            assertThat(fake.pendingRequests()).isZero();
            assertThat(other.pendingRequests()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "not-json", "{\"choices\":[]}",
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"fake\",\"type\":\"function\",\"function\":{\"name\":\"do_not_execute\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}"})
    void rejectsNonTextWithoutExecutingTools(String body) throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(200, body);
            var client = create(TestProperties.model(fake.baseUri().toString(), "fake-key"));
            assertThatThrownBy(() -> client.answer(conversation())).isInstanceOf(RemoteFailure.class);
            fake.take();
            assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void rejectsBlankTextWithoutLeakingOrRetrying() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(200, completion("   ", "stop"));
            var client = create(TestProperties.model(fake.baseUri().toString(), "fake-key"));
            assertThatThrownBy(() -> client.answer(conversation())).isInstanceOf(RemoteFailure.class);
            fake.take();
            assertThat(fake.pendingRequests()).isZero();
        }
    }
    private io.github.wochen5770.talkweave.runtime.AssistantProperties.Model budget(FakeHttpService fake, int requestSeconds, int totalSeconds, int retries) {
        var c = TestProperties.model(fake.baseUri().toString(), "fake-key");
        return new io.github.wochen5770.talkweave.runtime.AssistantProperties.Model(c.apiBaseUrl(), c.apiKey(), c.name(), c.systemPrompt(), true,
                c.contextCapacity(), c.outputBudget(), c.safetyMargin(), c.historyRounds(), java.time.Duration.ofSeconds(requestSeconds), java.time.Duration.ofSeconds(totalSeconds), retries);
    }
    @Test void rateLimitRetriesOnlyWithinAttemptBudgetAndPreservesRequest() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(429, "{\"error\":\"secret-provider-body\"}", Map.of("Retry-After", "0"));
            fake.enqueue(200, completion("answer", "stop"));
            assertThat(create(budget(fake, 1, 3, 2)).answer(conversation()).text()).isEqualTo("answer");
            assertThat(fake.take().body()).isEqualTo(fake.take().body()); assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void retryCountExhaustionDoesNotFallbackOrExposeRawError() throws Exception {
        try (var fake = new FakeHttpService()) {
            for (int i = 0; i < 3; i++) fake.enqueue(429, "secret-provider-body", Map.of("Retry-After", "0"));
            assertThatThrownBy(() -> create(budget(fake, 1, 3, 2)).answer(conversation())).isInstanceOf(RemoteFailure.class).hasMessageNotContaining("secret-provider-body");
            fake.take(); fake.take(); fake.take(); assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void serverWaitBeyondTotalBudgetEndsWithoutSleepingOrAnotherAttempt() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(429, "{}", Map.of("Retry-After", "9223372036854775807"));
            long start = System.nanoTime();
            assertThatThrownBy(() -> create(budget(fake, 1, 2, 5)).answer(conversation())).isInstanceOf(RemoteFailure.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(1));
            fake.take(); assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void dispatchedTimeoutNeverRetriesEvenIfAnotherAnswerIsAvailable() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueueDelayed(200, completion("late", "stop"), 1500);
            fake.enqueue(200, completion("must-not-use", "stop"));
            assertThatThrownBy(() -> create(budget(fake, 1, 3, 2)).answer(conversation())).isInstanceOf(RemoteFailure.class)
                    .satisfies(e -> assertThat(((RemoteFailure)e).kind()).isEqualTo(RemoteFailure.Kind.TIMEOUT));
            fake.take(); assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void totalBudgetIncludesBackoffAndFinalAttempt() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(429, "{}", Map.of("Retry-After", "1"));
            fake.enqueueDelayed(200, completion("late", "stop"), 1500);
            long start = System.nanoTime();
            assertThatThrownBy(() -> create(budget(fake, 2, 2, 5)).answer(conversation())).isInstanceOf(RemoteFailure.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofMillis(2400));
            fake.take(); fake.take(); assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void retryAfterDatesAndOnlyDefinitelyUnsentConnectionsAreRecognized() {
        var now = java.time.Instant.parse("2026-09-28T00:00:00Z");
        assertThat(CompatibleChatClient.retryAfter("Mon, 28 Sep 2026 00:00:03 GMT", now)).isEqualTo(java.time.Duration.ofSeconds(3));
        assertThat(CompatibleChatClient.retryAfter("Sun, 27 Sep 2026 00:00:00 GMT", now)).isEqualTo(java.time.Duration.ZERO);
        assertThat(CompatibleChatClient.retryAfter("malformed", now)).isNull();
        assertThat(CompatibleChatClient.definitelyUnsent(new java.net.ConnectException())).isTrue();
        assertThat(CompatibleChatClient.definitelyUnsent(new java.net.http.HttpConnectTimeoutException("fake"))).isTrue();
        assertThat(CompatibleChatClient.definitelyUnsent(new java.net.http.HttpTimeoutException("fake"))).isFalse();
        assertThat(CompatibleChatClient.definitelyUnsent(new java.io.IOException("connection reset after dispatch"))).isFalse();
    }

    private static ModelRequestContext requestContext() {
        return new ModelRequestContext("private-user", "private-binding", "private-conversation", 7, 2, 3, 1);
    }
    private CompatibleChatClient managed(io.github.wochen5770.talkweave.runtime.AssistantProperties.Model config) {
        var client = new CompatibleChatClient(config, 1L); clients.add(client); return client;
    }
    private static String withUsage(String usage, String text) {
        String content = completion(text, "stop");
        return content.substring(0, content.length() - 1) + ",\"usage\":" + usage + "}";
    }
    private static final class Observer implements ModelAttemptObserver {
        final java.util.List<Integer> starts = new java.util.ArrayList<>();
        final java.util.List<Outcome> outcomes = new java.util.ArrayList<>();
        final java.util.List<TokenUsage> counts = new java.util.ArrayList<>();
        @Override public void beforeAttempt(int number) { starts.add(number); }
        @Override public void afterAttempt(int number, Outcome outcome, TokenUsage usage) { outcomes.add(outcome); counts.add(usage); }
    }
    @Test void reportsActualCountsWithoutAddingAnyVendorOrIdentityFields() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(200, withUsage("{\"prompt_tokens\":120,\"completion_tokens\":9,\"prompt_tokens_details\":{\"cached_tokens\":80}}", "answer"));
            var observer = new Observer();
            var result = managed(TestProperties.model(fake.baseUri().toString(), "fake-key")).answer(requestContext(), conversation(), observer);
            assertThat(result.usage()).isEqualTo(new TokenUsage(120L, 9L, 80L, TokenUsage.Status.REPORTED));
            assertThat(observer.starts).containsExactly(1);
            assertThat(observer.counts).containsExactly(result.usage());
            assertThat(observer.outcomes).containsExactly(ModelAttemptObserver.Outcome.SUCCEEDED);
            String body = fake.take().body();
            assertThat(body).doesNotContain("private-user", "private-binding", "private-conversation", "cache_control", "prompt_cache_key");
            var fields = new java.util.HashSet<String>(); json.readTree(body).fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("model", "messages", "max_tokens", "stream");
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "null|UNKNOWN", "{}|UNKNOWN", "{\"prompt_tokens\":0,\"completion_tokens\":0,\"prompt_tokens_details\":{\"cached_tokens\":0}}|REPORTED",
        "{\"prompt_tokens\":5}|PARTIAL", "{\"prompt_tokens\":-1}|INVALID", "{\"prompt_tokens\":1.5}|INVALID",
        "{\"prompt_tokens\":9223372036854775808}|INVALID", "{\"prompt_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":6}}|INVALID",
        "{\"input_tokens\":8,\"cache_read_input_tokens\":4}|UNKNOWN", "[]|INVALID"})
    void boundedRawParserDoesNotFabricateUsage(String counts, TokenUsage.Status expected) throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(200, withUsage(counts, "answer"));
            var reply = create(TestProperties.model(fake.baseUri().toString(), "fake-key")).answer(conversation());
            assertThat(reply.text()).isEqualTo("answer"); assertThat(reply.usage().status()).isEqualTo(expected);
        }
    }
    @Test void countsAreNotNarrowedToLibraryIntegers() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(200, withUsage("{\"prompt_tokens\":4000000000,\"completion_tokens\":3,\"prompt_tokens_details\":{\"cached_tokens\":3000000000}}", "answer"));
            assertThat(create(TestProperties.model(fake.baseUri().toString(), "fake-key")).answer(conversation()).usage())
                    .isEqualTo(new TokenUsage(4_000_000_000L, 3L, 3_000_000_000L, TokenUsage.Status.REPORTED));
        }
    }
    @Test void everyRetryIsObservedButEmptyAnswersRetainUsageAndNeverRetry() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(429, "{}", Map.of("Retry-After", "0"));
            fake.enqueue(200, withUsage("{\"prompt_tokens\":10,\"completion_tokens\":2}", "   "));
            var observer = new Observer();
            assertThatThrownBy(() -> managed(budget(fake, 1, 3, 2)).answer(requestContext(), conversation(), observer)).isInstanceOf(RemoteFailure.class);
            assertThat(observer.starts).containsExactly(1, 2);
            assertThat(observer.counts).containsExactly(TokenUsage.unknown(), TokenUsage.normalize(10, 2, null));
            assertThat(observer.outcomes).containsExactly(ModelAttemptObserver.Outcome.FAILED, ModelAttemptObserver.Outcome.FAILED);
            fake.take(); fake.take(); assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void persistenceFailureAfterResponseNeverCallsModelAgain() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(200, completion("answer", "stop"));
            var observer = new ModelAttemptObserver() {
                @Override public void beforeAttempt(int number) { }
                @Override public void afterAttempt(int number, Outcome outcome, TokenUsage usage) { throw new IllegalStateException("synthetic store unavailable"); }
            };
            assertThatThrownBy(() -> managed(budget(fake, 1, 3, 2)).answer(requestContext(), conversation(), observer)).isInstanceOf(IllegalStateException.class);
            fake.take(); assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void authorizationOrSnapshotFailurePreventsDispatch() throws Exception {
        try (var fake = new FakeHttpService()) {
            var observer = new ModelAttemptObserver() {
                @Override public void beforeAttempt(int number) { throw new IllegalStateException("scope revoked"); }
                @Override public void afterAttempt(int number, Outcome outcome, TokenUsage usage) { throw new AssertionError(); }
            };
            assertThatThrownBy(() -> managed(budget(fake, 1, 3, 2)).answer(requestContext(), conversation(), observer)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> create(budget(fake, 1, 3, 2)).answer(requestContext(), conversation(), observer)).isInstanceOf(IllegalArgumentException.class);
            assertThat(fake.pendingRequests()).isZero();
        }
    }
    @Test void timedOutAttemptIsUnknownAndNotReplayed() throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueueDelayed(200, withUsage("{\"prompt_tokens\":1}", "late"), 1500);
            var observer = new Observer();
            assertThatThrownBy(() -> managed(budget(fake, 1, 3, 2)).answer(requestContext(), conversation(), observer)).isInstanceOf(RemoteFailure.class);
            assertThat(observer.outcomes).containsExactly(ModelAttemptObserver.Outcome.UNKNOWN);
            assertThat(observer.counts).containsExactly(TokenUsage.unknown());
            fake.take(); assertThat(fake.pendingRequests()).isZero();
        }
    }

    @Test void strictRelayAcceptsStablePrefixAndFullHistoryWithoutSpecialCacheFields() throws Exception {
        try (var fake = new FakeHttpService(request -> {
            try {
                var fields = new java.util.HashSet<String>(); json.readTree(request.body()).fieldNames().forEachRemaining(fields::add);
                return fields.equals(java.util.Set.of("model", "messages", "max_tokens", "stream"));
            } catch (Exception invalid) { return false; }
        })) {
            var config = TestProperties.model(fake.baseUri().toString(), "fake-key");
            var context = new io.github.wochen5770.talkweave.conversation.ContextBudget(config);
            var first = context.prepare(List.of(), "question one").orElseThrow();
            fake.enqueue(200, completion("answer one", "stop"));
            fake.enqueue(200, completion("answer two", "stop"));
            var client = managed(config);
            var firstReply = client.answer(requestContext(), first, ModelAttemptObserver.noop());
            var second = context.prepare(List.of(new DialogueMessage(USER, "question one"),
                    new DialogueMessage(ASSISTANT, firstReply.text())), "question two").orElseThrow();
            assertThat(client.answer(requestContext(), second, ModelAttemptObserver.noop()).text()).isEqualTo("answer two");
            var a = json.readTree(fake.take().body()).get("messages");
            var b = json.readTree(fake.take().body()).get("messages");
            assertThat(a.size()).isEqualTo(2); assertThat(b.size()).isEqualTo(4);
            assertThat(b.get(0)).isEqualTo(a.get(0)); assertThat(b.get(1)).isEqualTo(a.get(1));
            assertThat(b.get(2).get("content").asText()).isEqualTo("answer one");
            assertThat(b.get(3).get("content").asText()).isEqualTo("question two");
            assertThat(firstReply.usage()).isEqualTo(TokenUsage.unknown());
        }
    }
    @Test void boundedContextDropsOldTurnsEvenWhenThatChangesCachePrefix() throws Exception {
        try (var fake = new FakeHttpService()) {
            var c = TestProperties.model(fake.baseUri().toString(), "fake-key");
            var small = new io.github.wochen5770.talkweave.runtime.AssistantProperties.Model(c.apiBaseUrl(), c.apiKey(), c.name(), c.systemPrompt(), true,
                    1024, 128, 64, 1, c.requestTimeout(), c.totalTimeBudget(), c.maxRetries());
            var history = List.of(new DialogueMessage(USER,"old-".repeat(300)), new DialogueMessage(ASSISTANT,"old reply"),
                    new DialogueMessage(USER,"recent"),new DialogueMessage(ASSISTANT,"recent reply"));
            var prompt = new io.github.wochen5770.talkweave.conversation.ContextBudget(small).prepare(history,"now").orElseThrow();
            fake.enqueue(200, completion("answer", "stop"));
            managed(small).answer(requestContext(), prompt, ModelAttemptObserver.noop());
            String body = fake.take().body();
            assertThat(body).contains("recent", "recent reply", "now").doesNotContain("old-", "old reply");
        }
    }
}
