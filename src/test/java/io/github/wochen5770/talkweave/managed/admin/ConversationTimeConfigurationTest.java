package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.conversation.ConversationTimeContext;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ConversationTimeConfigurationTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC);
    @Test void defaultAndOverrideAreIndependentOfHostTimezone() {
        var config = new ManagedAdminConfiguration();
        assertThat(config.conversationTimeContext(clock, new MockEnvironment()).snapshot(clock.millis()).text())
                .contains("2026-09-30 08:00:00", "[Asia/Shanghai]");
        assertThat(config.conversationTimeContext(clock, new MockEnvironment().withProperty("managed.conversation.time-zone", "UTC"))
                .snapshot(clock.millis()).text()).contains("2026-09-30 00:00:00", "[UTC]");
    }
    @Test void invalidZoneFailsBeanCreationInsteadOfFallingBack() {
        new ApplicationContextRunner().withBean(ConversationTimeContext.class, () -> new ManagedAdminConfiguration()
                .conversationTimeContext(clock, new MockEnvironment().withProperty("managed.conversation.time-zone", "secret-invalid-zone")))
                .run(context -> assertThat(context).hasFailed());
    }
}
