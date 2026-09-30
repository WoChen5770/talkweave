package io.github.wochen5770.talkweave.conversation;

import io.github.wochen5770.talkweave.runtime.ConfigurationProblem;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/** Per-request trusted context. Never stored with history or appended to user text. */
public final class ConversationTimeContext {
    public static final String DEFAULT_ZONE = "Asia/Shanghai";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss EEEE xxx '['VV']'", Locale.SIMPLIFIED_CHINESE);
    private final Clock clock;
    private final ZoneId zone;

    public ConversationTimeContext(Clock clock, String zoneId) {
        this.clock = Objects.requireNonNull(clock);
        try {
            if (zoneId == null || zoneId.isBlank()) throw new DateTimeException("Missing zone");
            this.zone = ZoneId.of(zoneId);
        } catch (DateTimeException invalid) {
            // Configuration values may contain secrets pasted into the wrong field.
            throw new ConfigurationProblem("managed.conversation.time-zone", "must be a valid explicit time zone");
        }
    }

    public static ConversationTimeContext systemDefault() {
        return new ConversationTimeContext(Clock.systemUTC(), DEFAULT_ZONE);
    }

    public DialogueMessage snapshot(long receivedAtMillis) {
        Instant now = clock.instant(); // Exactly one sample per assembled request, not per retry.
        String text = "服务端时间上下文：\n当前时间（本轮请求组装时）：" + FORMAT.format(now.atZone(zone))
                + "\n消息接收时间：" + FORMAT.format(Instant.ofEpochMilli(receivedAtMillis).atZone(zone))
                + "\n当前时间用于回答现在的日期和时间；消息接收时间用于理解排队消息中的相对日期。"
                + "这不是实时新闻或外部事件信息。";
        return new DialogueMessage(DialogueMessage.Role.SYSTEM, text);
    }
}
