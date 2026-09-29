package io.github.wochen5770.talkweave.runtime;

import io.github.wochen5770.talkweave.support.TestProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AssistantPropertiesTest {
    @TempDir Path data;

    @Test void acceptsGatewayPrefixAndUnboundOwner() {
        var properties = TestProperties.withModel(data, TestProperties.model("https://model.invalid/gateway/", "fake-key"));
        properties.validate();
        assertThat(properties.model().completionUri().toString()).isEqualTo("https://model.invalid/gateway/v1/chat/completions");
        assertThat(properties.wechat().isBound()).isFalse();
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
        var secure = new AssistantProperties.Model(base.apiBaseUrl(), base.apiKey(), base.name(), base.systemPrompt(), false,
                8192, 1024, 512, 20, base.requestTimeout(), base.totalTimeBudget(), 2);
        assertThatThrownBy(secure::validate).isInstanceOf(ConfigurationProblem.class);
    }

    @Test void rejectsMissingCredentialsAndInconsistentBudget() {
        assertThatThrownBy(() -> TestProperties.model("https://model.invalid", "").validate()).hasMessageContaining("api-key");
        var budget = new AssistantProperties.Model("https://model.invalid", "fake-key", "test", "test", false,
                1024, 1024, 512, 20, Duration.ofSeconds(2), Duration.ofSeconds(1), 9);
        assertThatThrownBy(budget::validate).hasMessageContaining("output-budget");
    }

    @Test void rejectsPartialOwnerBindingAndInvalidLimits() {
        var defaults = TestProperties.valid(data);
        var partialOwner = new AssistantProperties.Wechat("fake-bot", "", Duration.ofSeconds(15), Duration.ofSeconds(45), Duration.ofSeconds(480), 2000);
        assertThatThrownBy(() -> new AssistantProperties(defaults.model(), partialOwner, defaults.storage(), defaults.shutdownGrace()).validate())
                .hasMessageContaining("requires both");
        var badStorage = new AssistantProperties.Storage(data.toString(), 0, -1, Duration.ZERO);
        assertThatThrownBy(() -> new AssistantProperties(defaults.model(), defaults.wechat(), badStorage, defaults.shutdownGrace()).validate())
                .hasMessageContaining("max-pending-events");
    }

    @Test void boundsAllTimeoutsAndRetryCounts() {
        var model = new AssistantProperties.Model("https://model.invalid", "fake-key", "test", "test", false,
                8192, 1024, 512, 20, Duration.ofSeconds(5), Duration.ofSeconds(4), 2);
        assertThatThrownBy(model::validate).hasMessageContaining("total-time-budget");
        var retries = new AssistantProperties.Model("https://model.invalid", "fake-key", "test", "test", false,
                8192, 1024, 512, 20, Duration.ofSeconds(5), Duration.ofSeconds(10), 6);
        assertThatThrownBy(retries::validate).hasMessageContaining("max-retries");
    }

    @Test void stringRepresentationsNeverContainSecrets() {
        var properties = TestProperties.valid(data);
        assertThat(properties.toString() + properties.model() + properties.wechat() + properties.storage())
                .doesNotContain("fake-key", "model.invalid", data.toString());
    }
}
