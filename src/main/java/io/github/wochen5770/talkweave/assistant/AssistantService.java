package io.github.wochen5770.talkweave.assistant;

import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import java.util.List;

/** Business boundary: future tool execution can replace the implementation, not the channel. */
public interface AssistantService {
    Reply answer(List<DialogueMessage> messages);
    record Reply(String text, boolean truncated, io.github.wochen5770.talkweave.model.TokenUsage usage) {
        public Reply(String text, boolean truncated) { this(text, truncated, io.github.wochen5770.talkweave.model.TokenUsage.unknown()); }
        public Reply { java.util.Objects.requireNonNull(usage); }
        @Override public String toString() { return "Reply[text=REDACTED, truncated=" + truncated + "]"; }
    }
}
