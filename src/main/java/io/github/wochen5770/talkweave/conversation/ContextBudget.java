package io.github.wochen5770.talkweave.conversation;

import io.github.wochen5770.talkweave.runtime.AssistantProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static io.github.wochen5770.talkweave.conversation.DialogueMessage.Role.*;

/** Conservative UTF-8-byte heuristic, not a claim about an unknown provider's exact tokenizer. */
public final class ContextBudget {
    private final AssistantProperties.Model config;
    public ContextBudget(AssistantProperties.Model config) { config.validate(); this.config = config; }

    public Optional<List<DialogueMessage>> prepare(List<DialogueMessage> history, String input) {
        if (history.size() % 2 != 0) throw new IllegalArgumentException("History must contain complete turns");
        for (int i = 0; i < history.size(); i += 2) {
            if (history.get(i).role() != USER || history.get(i + 1).role() != ASSISTANT) throw new IllegalArgumentException("History roles must alternate");
        }
        var system = new DialogueMessage(SYSTEM, config.systemPrompt());
        var current = new DialogueMessage(USER, input);
        long capacity = (long) config.contextCapacity() - config.outputBudget() - config.safetyMargin();
        long minimum = estimate(List.of(system, current));
        if (minimum > capacity) return Optional.empty();
        int start = Math.max(0, history.size() - config.historyRounds() * 2);
        long total = minimum;
        for (int i = start; i < history.size(); i++) total += messageCost(history.get(i));
        while (total > capacity && start < history.size()) {
            total -= messageCost(history.get(start)) + messageCost(history.get(start + 1));
            start += 2;
        }
        var messages = new ArrayList<DialogueMessage>();
        messages.add(system);
        messages.addAll(history.subList(start, history.size()));
        messages.add(current);
        return Optional.of(List.copyOf(messages));
    }
    public static long estimate(List<DialogueMessage> messages) {
        return 16L + messages.stream().mapToLong(ContextBudget::messageCost).sum();
    }
    private static long messageCost(DialogueMessage message) { return message.text().getBytes(StandardCharsets.UTF_8).length + 32L; }
}