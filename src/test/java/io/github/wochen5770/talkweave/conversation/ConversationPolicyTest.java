package io.github.wochen5770.talkweave.conversation;

import io.github.wochen5770.talkweave.model.ModelConfiguration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.wochen5770.talkweave.conversation.DialogueMessage.Role.*;
import static org.assertj.core.api.Assertions.*;

class ConversationPolicyTest {
    private ContextBudget budget(int capacity, int history) {
        return new ContextBudget(new ModelConfiguration("https://example.invalid", "fake", "unknown-model", "system", false,
                capacity, 100, 10, history, Duration.ofSeconds(5), Duration.ofSeconds(10), 0));
    }
    @Test void removesOldestWholeTurnsByCapacityAndRoundCountWithoutMutatingHistory() {
        var history = List.of(new DialogueMessage(USER, "u1"), new DialogueMessage(ASSISTANT, "a1"), new DialogueMessage(USER, "u2"), new DialogueMessage(ASSISTANT, "a2"));
        assertThat(budget(300, 20).prepare(history, "current").orElseThrow()).extracting(DialogueMessage::text).containsExactly("system", "u2", "a2", "current");
        assertThat(budget(8192, 1).prepare(history, "current").orElseThrow()).extracting(DialogueMessage::text).containsExactly("system", "u2", "a2", "current");
        assertThat(budget(8192, 0).prepare(history, "current").orElseThrow()).hasSize(2);
        assertThat(history).hasSize(4);
        assertThat(budget(256, 20).prepare(history, "x".repeat(300))).isEmpty();
    }
    @Test void formatterPreservesCodePointsAndBothTruncationNotices() {
        String text = "你好🙂𠮷".repeat(100);
        String result = new ReplyFormatter(128).format(text, true);
        assertThat(result.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(128);
        assertThat(result).contains("模型达到输出上限", "已截断");
        assertThat(result).isEqualTo(new String(result.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThat(new ReplyFormatter(128).format("short", false)).isEqualTo("short");
        assertThat(new ReplyFormatter(128).format("short", true)).contains("short", "可能不完整").doesNotContain("已截断");
    }
    @Test void dynamicSystemCostIsRequiredEvenWithNoHistoryAndExactBoundaryFits() {
        var time = new DialogueMessage(SYSTEM, "time-".repeat(30));
        long minimum = ContextBudget.estimate(List.of(new DialogueMessage(SYSTEM, "system"), time, new DialogueMessage(USER, "now")));
        int capacity = Math.toIntExact(minimum + 110);
        assertThat(budget(capacity, 0).prepare(List.of(), "now", time).orElseThrow())
                .extracting(DialogueMessage::role).containsExactly(SYSTEM, SYSTEM, USER);
        assertThat(budget(capacity - 1, 0).prepare(List.of(), "now", time)).isEmpty();
        assertThat(budget(capacity - 1, 0).prepare(List.of(), "now")).isPresent();
    }
    @Test void trimsOnlyWholeOldTurnsAndNeverDropsRequiredTime() {
        var time = new DialogueMessage(SYSTEM, "time-".repeat(30));
        var history = List.of(new DialogueMessage(USER, "old"), new DialogueMessage(ASSISTANT, "old answer"),
                new DialogueMessage(USER, "recent"), new DialogueMessage(ASSISTANT, "recent answer"));
        var expected = List.of(new DialogueMessage(SYSTEM, "system"), history.get(2), history.get(3), time, new DialogueMessage(USER, "now"));
        int capacity = Math.toIntExact(ContextBudget.estimate(expected) + 110);
        assertThat(budget(capacity, 20).prepare(history, "now", time).orElseThrow()).containsExactlyElementsOf(expected);
        assertThat(budget(8192, 0).prepare(history, "now", time).orElseThrow()).containsExactly(expected.getFirst(), time, expected.getLast());
        assertThat(history).hasSize(4);
        assertThatThrownBy(() -> budget(8192, 20).prepare(history, "now", new DialogueMessage(USER, "fake time")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedHistoryIsRejectedInsteadOfSilentlyRepaired() {
        assertThatThrownBy(() -> budget(8192, 20).prepare(List.of(new DialogueMessage(USER, "unpaired")), "current")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget(8192, 20).prepare(List.of(new DialogueMessage(SYSTEM, "wrong"), new DialogueMessage(ASSISTANT, "reply")), "current")).isInstanceOf(IllegalArgumentException.class);
    }
}
