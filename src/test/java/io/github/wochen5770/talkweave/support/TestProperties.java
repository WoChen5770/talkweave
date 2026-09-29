package io.github.wochen5770.talkweave.support;

import io.github.wochen5770.talkweave.runtime.AssistantProperties;
import java.nio.file.Path;
import java.time.Duration;

public final class TestProperties {
    private TestProperties() { }
    public static AssistantProperties valid(Path path) {
        return new AssistantProperties(model("https://model.invalid", "fake-key-never-real"),
                new AssistantProperties.Wechat("", "", Duration.ofSeconds(15), Duration.ofSeconds(45), Duration.ofSeconds(480), 2000),
                new AssistantProperties.Storage(path.toString(), 1000, 0, Duration.ofSeconds(5)), Duration.ofSeconds(20));
    }
    public static AssistantProperties.Model model(String base, String key) {
        return new AssistantProperties.Model(base, key, "custom-test-model", "You are a test assistant.", true,
                8192, 1024, 512, 20, Duration.ofSeconds(5), Duration.ofSeconds(10), 2);
    }
    public static AssistantProperties withModel(Path path, AssistantProperties.Model model) {
        var base = valid(path);
        return new AssistantProperties(model, base.wechat(), base.storage(), base.shutdownGrace());
    }
}
