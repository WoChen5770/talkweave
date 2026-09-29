package io.github.personalassistant.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.personalassistant.conversation.DialogueMessage;
import io.github.personalassistant.runtime.RemoteFailure;
import io.github.personalassistant.support.FakeHttpService;
import io.github.personalassistant.support.TestProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.personalassistant.conversation.DialogueMessage.Role.*;
import static org.assertj.core.api.Assertions.*;

class CompatibleChatClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final java.util.List<CompatibleChatClient> clients = new java.util.ArrayList<>();
    private CompatibleChatClient create(io.github.personalassistant.runtime.AssistantProperties.Model config) {
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
    private io.github.personalassistant.runtime.AssistantProperties.Model budget(FakeHttpService fake, int requestSeconds, int totalSeconds, int retries) {
        var c = TestProperties.model(fake.baseUri().toString(), "fake-key");
        return new io.github.personalassistant.runtime.AssistantProperties.Model(c.apiBaseUrl(), c.apiKey(), c.name(), c.systemPrompt(), true,
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
}

