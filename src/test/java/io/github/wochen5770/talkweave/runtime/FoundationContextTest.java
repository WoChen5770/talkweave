package io.github.wochen5770.talkweave.runtime;

import io.github.wochen5770.talkweave.AssistantApplication;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/** Offline fail-closed startup. Explicit nonexistent import prevents use of any developer/NAS file. */
class FoundationContextTest {
    @TempDir Path directory;
    @Test void oldConfigurationCannotStartLegacyStorageOrCreateOldFiles() {
        new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AssistantApplication.class)
                .withPropertyValues("spring.profiles.active=managed", "EXTERNAL_SERVICES_CONFIG=" + directory.resolve("absent.yml"),
                        "managed.materials-directory=" + directory.resolve("materials"), "assistant.enabled=true",
                        "assistant.storage.directory=" + directory.resolve("old"), "assistant.model.api-key=synthetic-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("Invalid external service configuration")
                            .hasMessageNotContaining("synthetic-secret");
                });
        assertThat(directory.resolve("old")).doesNotExist();
        assertThat(directory.resolve("materials")).doesNotExist();
    }
    @Test void retiredClassesAndDriversAreNotOnTheRuntimeClasspath() {
        for (String name : new String[]{"org.sqlite.JDBC", "io.github.wochen5770.talkweave.persistence.SqliteStore",
                "io.github.wochen5770.talkweave.runtime.AssistantRuntime", "io.github.wochen5770.talkweave.runtime.AssistantProperties"})
            assertThatThrownBy(() -> Class.forName(name)).isInstanceOf(ClassNotFoundException.class);
    }
}

