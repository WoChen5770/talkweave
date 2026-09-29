package io.github.wochen5770.talkweave.managed.persistence;

/** Snapshot checked again before every operation; restoring a user cannot revive old work. */
public record ManagedScope(String userId, String bindingId, String botId, String senderId,
                           long generation, long authEpoch) {
    @Override public String toString() { return "ManagedScope[REDACTED]"; }
}