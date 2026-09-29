package io.github.wochen5770.talkweave.model;

/** Internal attribution only: never serialize these identifiers into prompts, headers or cache keys. */
public record ModelRequestContext(String userId, String bindingId, String conversationId, long eventSequence,
                                  long generation, long authEpoch, long modelVersion) {
    public ModelRequestContext {
        if (userId == null || userId.isBlank() || bindingId == null || bindingId.isBlank()
                || conversationId == null || conversationId.isBlank() || eventSequence < 1
                || generation < 1 || authEpoch < 0 || modelVersion < 1)
            throw new IllegalArgumentException("Invalid internal model request context");
    }
    @Override public String toString() { return "ModelRequestContext[REDACTED]"; }
}
