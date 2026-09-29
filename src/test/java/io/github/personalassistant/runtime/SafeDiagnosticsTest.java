package io.github.personalassistant.runtime;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class SafeDiagnosticsTest {
    @Test void dropsLibraryPromptsAndBodiesEvenAtWarn(CapturedOutput output) {
        LoggerFactory.getLogger("org.springframework.ai.openai.OpenAiChatModel")
                .warn("No choices for prompt: fake-private-conversation");
        LoggerFactory.getLogger("org.springframework.web.client.RestClient")
                .error("Upstream error: fake-private-response");
        assertThat(output.getAll()).doesNotContain("fake-private-conversation", "fake-private-response");
    }

    @Test void logsOnlyFixedFields(CapturedOutput output) {
        UUID correlation = UUID.randomUUID();
        SafeDiagnostics.failed(LoggerFactory.getLogger(getClass()), SafeDiagnostics.Operation.MODEL_CALL,
                SafeDiagnostics.Failure.TIMEOUT, correlation);
        assertThat(output.getOut()).contains("MODEL_CALL", "TIMEOUT", correlation.toString())
                .doesNotContain("Authorization", "api-key", "Bearer");
    }
}

