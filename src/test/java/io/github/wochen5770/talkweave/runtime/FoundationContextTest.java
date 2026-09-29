package io.github.wochen5770.talkweave.runtime;

import io.github.wochen5770.talkweave.persistence.SqliteStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class FoundationContextTest {
    @TempDir Path data;
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(io.github.wochen5770.talkweave.AssistantApplication.class)
                .withPropertyValues("spring.config.location=classpath:/application.yml", "assistant.enabled=false", "assistant.model.api-base-url=https://model.invalid",
                        "assistant.model.api-key=fake-secret-not-for-logs", "assistant.model.name=test-model",
                        "assistant.storage.directory=" + data);
    }

    @Test void startsOfflineAndClosesStorage() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SqliteStore.class);
            assertThat(context.getBean(SqliteStore.class).schemaVersion()).isEqualTo(2);
        });
        runner().run(context -> assertThat(context).hasNotFailed());
    }

    @Test void failsBeforeCreatingDataWhenConfigurationIsInvalid(CapturedOutput output) {
        runner().withPropertyValues("assistant.model.api-base-url=https://fake-secret-in-url@model.invalid").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(ConfigurationProblem.class);
            assertThat(data.resolve("assistant.sqlite")).doesNotExist();
        });
        assertThat(output.getAll()).doesNotContain("fake-secret-in-url", "fake-secret-not-for-logs");
    }
    @Test void fullSpringRuntimeWiringStartsAndStopsWithOnlySyntheticClients() {
        var wechat = org.mockito.Mockito.mock(io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.class);
        var model = org.mockito.Mockito.mock(io.github.wochen5770.talkweave.model.CompatibleChatClient.class);
        org.mockito.Mockito.when(wechat.requestQr(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                new io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.QrCode("synthetic-qr", "synthetic-display"));
        org.mockito.Mockito.when(wechat.pollQr(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(
                new io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginStatus(io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginPhase.WAIT, null, null));
        runner().withPropertyValues("assistant.enabled=true", "assistant.health-port=0", "assistant.shutdown-grace=1s")
                .withBean("syntheticWechat", io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.class, () -> wechat, definition -> definition.setPrimary(true))
                .withBean("syntheticModel", io.github.wochen5770.talkweave.model.CompatibleChatClient.class, () -> model, definition -> definition.setPrimary(true))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AssistantRuntime.class);
                    assertThat(context.getBean(AssistantRuntime.class).isRunning()).isTrue();
                    org.mockito.Mockito.verify(model, org.mockito.Mockito.never()).answer(org.mockito.ArgumentMatchers.anyList());
                });
        assertThat(data.resolve("login/qr.png")).doesNotExist();
        runner().run(context -> assertThat(context).hasNotFailed()); // The runtime released the DB lock.
    }
}

