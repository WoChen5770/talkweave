package io.github.wochen5770.talkweave.conversation;

public record DialogueMessage(Role role, String text) {
    public enum Role { SYSTEM, USER, ASSISTANT }
    public DialogueMessage {
        if (role == null || text == null || text.isBlank()) throw new IllegalArgumentException("A message requires a role and text");
    }
    @Override public String toString() { return "DialogueMessage[role=" + role + ", text=REDACTED]"; }
}
