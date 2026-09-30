package io.github.wochen5770.talkweave.runtime;

import io.github.wochen5770.talkweave.model.ModelConfiguration;

import io.github.wochen5770.talkweave.support.TestProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ModelConfigurationValidationTest {
    @TempDir Path data;

    @Test void acceptsGatewayPrefix() {
        var model = TestProperties.model("https://model.invalid/gateway/", "fake-key");
        model.validate();
        assertThat(model.completionUri().toString()).isEqualTo("https://model.invalid/gateway/v1/chat/completions");
    }

    @ParameterizedTest @ValueSource(strings = {"", "not a uri", "https://x.invalid/v1", "https://x.invalid/v1/",
            "https://x.invalid/v1/chat/completions", "https://fake-secret@x.invalid", "https://x.invalid?key=fake-secret",
            "https://x.invalid/#fake-secret", "https://x.invalid/a/../b", "https://x.invalid/%76%31",
            "https://x.invalid//prefix", "ftp://x.invalid", "http://public.invalid", "http://8.8.8.8"})
    void rejectsAmbiguousUrlsWithoutPrintingInput(String url) {
        assertThatThrownBy(() -> TestProperties.model(url, "fake-key").validate())
                .isInstanceOf(ConfigurationProblem.class).hasMessageContaining("api-base-url")
                .hasMessageNotContaining("fake-secret");
    }

    @Test void localHttpNeedsExplicitOptIn() {
        var base = TestProperties.model("http://127.0.0.1:1234", "fake-key");
        base.validate();
        var secure = new ModelConfiguration(base.apiBaseUrl(), base.apiKey(), base.name(), base.systemPrompt(), false,
                8192, 1024, 512, 20, base.requestTimeout(), base.totalTimeBudget(), 2);
        assertThatThrownBy(secure::validate).isInstanceOf(ConfigurationProblem.class);
    }

    @Test void rejectsMissingCredentialsAndInconsistentBudget() {
        assertThatThrownBy(() -> TestProperties.model("https://model.invalid", "").validate()).hasMessageContaining("api-key");
        var budget = new ModelConfiguration("https://model.invalid", "fake-key", "test", "test", false,
                1024, 1024, 512, 20, Duration.ofSeconds(2), Duration.ofSeconds(1), 9);
        assertThatThrownBy(budget::validate).hasMessageContaining("output-budget");
    }

    @Test void boundsAllTimeoutsAndRetryCounts() {
        var model = new ModelConfiguration("https://model.invalid", "fake-key", "test", "test", false,
                8192, 1024, 512, 20, Duration.ofSeconds(5), Duration.ofSeconds(4), 2);
        assertThatThrownBy(model::validate).hasMessageContaining("total-time-budget");
        var retries = new ModelConfiguration("https://model.invalid", "fake-key", "test", "test", false,
                8192, 1024, 512, 20, Duration.ofSeconds(5), Duration.ofSeconds(10), 6);
        assertThatThrownBy(retries::validate).hasMessageContaining("max-retries");
    }

    @Test void stringRepresentationsNeverContainSecrets() {
        var model = TestProperties.model("https://model.invalid", "fake-key");
        assertThat(model.toString())
                .doesNotContain("fake-key", "model.invalid", data.toString());
    }
}
