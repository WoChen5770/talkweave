package io.github.wochen5770.talkweave.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelConfigurationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void readsExistingSettingsJsonAndPreservesAllFieldsOnRoundTrip() throws Exception {
        String oldJson = """
                {"apiBaseUrl":"https://model.invalid/gateway","apiKey":"synthetic-private-key",
                 "name":"test-model","systemPrompt":"固定提示🙂","allowInsecureLocalHttp":false,
                 "contextCapacity":8192,"outputBudget":1024,"safetyMargin":512,"historyRounds":20,
                 "requestTimeout":15.000000000,"totalTimeBudget":30.000000000,"maxRetries":2}
                """;
        var model = json.readValue(oldJson, ModelConfiguration.class);
        model.validate();
        assertThat(model.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(model.totalTimeBudget()).isEqualTo(Duration.ofSeconds(30));
        assertThat(model.completionUri().toString()).isEqualTo("https://model.invalid/gateway/v1/chat/completions");
        var serialized = json.writeValueAsString(model);
        assertThat(json.readValue(serialized, ModelConfiguration.class)).isEqualTo(model);
        var fields = new HashSet<String>(); json.readTree(serialized).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("apiBaseUrl", "apiKey", "name", "systemPrompt", "allowInsecureLocalHttp",
                "contextCapacity", "outputBudget", "safetyMargin", "historyRounds", "requestTimeout", "totalTimeBudget", "maxRetries");
        assertThat(json.readTree(serialized).get("systemPrompt").asText()).isEqualTo("固定提示🙂");
        assertThat(model.toString()).doesNotContain("synthetic-private-key", "model.invalid", "固定提示");
    }
}
