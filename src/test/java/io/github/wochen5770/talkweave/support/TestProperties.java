package io.github.wochen5770.talkweave.support;

import io.github.wochen5770.talkweave.model.ModelConfiguration;

import java.time.Duration;

public final class TestProperties {
    private TestProperties() { }
    public static ModelConfiguration model(String base, String key) {
        return new ModelConfiguration(base, key, "custom-test-model", "You are a test assistant.", true,
                8192, 1024, 512, 20, Duration.ofSeconds(5), Duration.ofSeconds(10), 2);
    }
}
