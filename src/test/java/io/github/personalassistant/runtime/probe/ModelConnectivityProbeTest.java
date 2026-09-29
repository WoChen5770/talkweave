package io.github.personalassistant.runtime.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.personalassistant.assistant.AssistantService;
import io.github.personalassistant.model.CompatibleChatClient;
import io.github.personalassistant.runtime.ConfigurationProblem;
import io.github.personalassistant.support.FakeHttpService;
import io.github.personalassistant.support.TestProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ModelConnectivityProbeTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    private Path configFile(String base) throws Exception {
        Path file = temp.resolve("model.yml");
        Files.writeString(file, """
                assistant:
                  model:
                    api-base-url: '%s'
                    api-key: 'synthetic-api-key'
                    name: 'test-model'
                    system-prompt: 'private-configured-prompt-not-for-probe'
                    context-capacity: 8192
                    allow-insecure-local-http: true
                """.formatted(base));
        return file;
    }

    private String completion(String text, String finish) throws Exception {
        return json.writeValueAsString(Map.of("id", "synthetic-response", "object", "chat.completion", "model", "test-model",
                "choices", List.of(Map.of("index", 0, "finish_reason", finish,
                        "message", Map.of("role", "assistant", "content", text)))));
    }

    @Test void loadsLocalYamlWithDefaultsAndCapsWithoutMutating(CapturedOutput output) throws Exception {
        var original = ModelConnectivityProbe.loadConfig(configFile("https://example.invalid/gateway"));
        var capped = ModelConnectivityProbe.probeConfig(original);
        assertThat(original.outputBudget()).isEqualTo(1024);
        assertThat(capped.outputBudget()).isEqualTo(256);
        assertThat(capped.maxRetries()).isZero();
        assertThat(capped.systemPrompt()).doesNotContain("private-configured");
        assertThat(capped.completionUri().toString()).isEqualTo("https://example.invalid/gateway/v1/chat/completions");
        assertThat(ModelConnectivityProbe.runCommand(new String[]{"--check-config", temp.resolve("model.yml").toString()})).isZero();
        assertThat(output.getAll()).doesNotContain("synthetic-api-key", "private-configured");
    }

    @Test void malformedConfigAndMissingConsentFailWithoutLeakingOrCalling(CapturedOutput output) throws Exception {
        try (var fake = new FakeHttpService()) {
            Path file = configFile(fake.baseUri().toString());
            assertThat(ModelConnectivityProbe.runCommand(new String[]{"--run", file.toString(), temp.resolve("run").toString()})).isEqualTo(2);
            assertThat(fake.pendingRequests()).isZero();
            assertThat(temp.resolve("run")).doesNotExist();
            Files.writeString(file, "assistant: { model: [synthetic-secret-malformed");
            assertThatThrownBy(() -> ModelConnectivityProbe.loadConfig(file))
                    .isInstanceOf(ConfigurationProblem.class).hasMessageNotContaining("synthetic-secret").hasNoCause();
            assertThat(ModelConnectivityProbe.runCommand(new String[]{"--check-config", file.toString()})).isEqualTo(2);
            assertThat(output.getAll()).doesNotContain("synthetic-secret-malformed", "synthetic-api-key");
        }
    }

    @Test void makesExactlyTwoRequestsAndCarriesRealFirstReplyWithoutPersistingTranscript(CapturedOutput output) throws Exception {
        String nonce = "synthetic-nonce-123456";
        Path run = temp.resolve("success");
        try (var fake = new FakeHttpService()) {
            fake.enqueue(200, completion("synthetic-first-answer", "stop"));
            fake.enqueue(200, completion(nonce, "stop"));
            var config = ModelConnectivityProbe.probeConfig(TestProperties.model(fake.baseUri().toString(), "synthetic-api-key"));
            var probe = new ModelConnectivityProbe(new PrivateProbeFiles(run), config);
            try (var client = new CompatibleChatClient(config)) { probe.execute(client, nonce); }
            var first = json.readTree(fake.take().body());
            var secondRequest = fake.take();
            var second = json.readTree(secondRequest.body());
            assertThat(first.path("messages")).hasSize(2);
            assertThat(second.path("messages")).hasSize(4);
            assertThat(second.path("messages").findValuesAsText("role")).containsExactly("system", "user", "assistant", "user");
            assertThat(second.at("/messages/2/content").asText()).isEqualTo("synthetic-first-answer");
            assertThat(second.at("/messages/3/content").asText()).doesNotContain(nonce);
            assertThat(second.path("max_tokens").asInt()).isEqualTo(256);
            assertThat(second.properties().stream().map(Map.Entry::getKey)).containsExactlyInAnyOrder("model", "messages", "stream", "max_tokens");
            assertThat(secondRequest.uri().getPath()).isEqualTo("/v1/chat/completions");
            assertThat(fake.pendingRequests()).isZero();
        }
        var evidence = json.readTree(Files.readString(run.resolve("evidence.json")));
        assertThat(evidence.path("phase").asText()).isEqualTo("COMPLETED");
        assertThat(evidence.path("attemptsStarted").asInt()).isEqualTo(2);
        assertThat(evidence.path("contextRecallMatched").asBoolean()).isTrue();
        assertThat(Files.readString(run.resolve("evidence.json"))).doesNotContain(nonce, "synthetic-first-answer", "synthetic-api-key");
        assertThat(output.getAll()).doesNotContain(nonce, "synthetic-first-answer", "synthetic-api-key");
    }

    @Test void firstHttpFailureStopsAfterOneAttemptAndCannotResumeDirectory(CapturedOutput output) throws Exception {
        try (var fake = new FakeHttpService()) {
            fake.enqueue(401, "{\"error\":\"synthetic-secret-response\"}");
            Path config = configFile(fake.baseUri().toString());
            Path run = temp.resolve("failed");
            String[] args = {"--allow-paid-model-check", config.toString(), run.toString()};
            assertThat(ModelConnectivityProbe.runCommand(args)).isEqualTo(1);
            fake.take();
            assertThat(fake.pendingRequests()).isZero();
            assertThat(ModelConnectivityProbe.runCommand(args)).isEqualTo(1);
            assertThat(fake.pendingRequests()).isZero();
            var status = json.readTree(Files.readString(run.resolve("status.json")));
            assertThat(status.path("phase").asText()).isEqualTo("FAILED_HTTP");
            assertThat(status.path("attemptsStarted").asInt()).isEqualTo(1);
            assertThat(status.path("repliesReceived").asInt()).isZero();
            assertThat(output.getAll()).doesNotContain("synthetic-secret-response", "synthetic-api-key");
        }
    }

    @Test void truncationAndIncorrectRecallAreNotSuccess() throws Exception {
        var config = ModelConnectivityProbe.probeConfig(TestProperties.model("https://example.invalid", "synthetic-api-key"));
        Path shortRun = temp.resolve("truncated");
        var truncated = new ModelConnectivityProbe(new PrivateProbeFiles(shortRun), config);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        truncated.execute(messages -> { calls.incrementAndGet(); return new AssistantService.Reply("partial", true); }, "nonce");
        assertThat(calls).hasValue(1);
        assertThat(json.readTree(Files.readString(shortRun.resolve("status.json"))).path("phase").asText()).isEqualTo("FIRST_REPLY_TRUNCATED");
        Path wrongRun = temp.resolve("wrong");
        var wrong = new ModelConnectivityProbe(new PrivateProbeFiles(wrongRun), config);
        wrong.execute(messages -> new AssistantService.Reply("not-the-code", false), "nonce");
        assertThat(json.readTree(Files.readString(wrongRun.resolve("status.json"))).path("phase").asText()).isEqualTo("CONTEXT_NOT_CONFIRMED");
    }
}