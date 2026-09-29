package io.github.wochen5770.talkweave.assistant;

import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import java.util.List;

/** Business boundary: future tool execution can replace the implementation, not the channel. */
public interface AssistantService {
    Reply answer(List<DialogueMessage> messages);
    record Reply(String text, boolean truncated) {
        @Override public String toString() { return "Reply[text=REDACTED, truncated=" + truncated + "]"; }
    }
}
