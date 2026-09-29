package io.github.wochen5770.talkweave.model;

import java.math.BigDecimal;
import java.util.Map;

/** Provider-reported counts only. Unknown is null, never an estimated or fabricated zero. */
public record TokenUsage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Status status) {
    public enum Status { REPORTED, PARTIAL, UNKNOWN, INVALID }
    public TokenUsage {
        if (status == null || negative(inputTokens) || negative(outputTokens) || negative(cachedInputTokens)
                || (inputTokens != null && cachedInputTokens != null && cachedInputTokens > inputTokens)) {
            throw new IllegalArgumentException("Invalid token usage");
        }
    }
    public static TokenUsage unknown() { return new TokenUsage(null, null, null, Status.UNKNOWN); }
    public static TokenUsage normalize(Object input, Object output, Object cached) {
        var i = count(input); var o = count(output); var c = count(cached);
        boolean invalid = i.invalid || o.invalid || c.invalid;
        Long cachedValue = c.value;
        if (i.value != null && cachedValue != null && cachedValue > i.value) { cachedValue = null; invalid = true; }
        Status status = invalid ? Status.INVALID
                : i.value == null && o.value == null && cachedValue == null ? Status.UNKNOWN
                : i.value != null && o.value != null && cachedValue != null ? Status.REPORTED : Status.PARTIAL;
        return new TokenUsage(i.value, o.value, cachedValue, status);
    }
    /** Standard compatible fields only; similarly named vendor fields have no assumed semantics. */
    public static TokenUsage fromCompatibleUsage(Map<String, ?> usage) {
        if (usage == null) return unknown();
        Object detail = usage.get("prompt_tokens_details");
        Object cached = detail instanceof Map<?, ?> details ? details.get("cached_tokens") : null;
        TokenUsage result = normalize(usage.get("prompt_tokens"), usage.get("completion_tokens"), cached);
        if (detail != null && !(detail instanceof Map<?, ?>)) {
            return new TokenUsage(result.inputTokens, result.outputTokens, result.cachedInputTokens, Status.INVALID);
        }
        return result;
    }
    private record Count(Long value, boolean invalid) { }
    private static Count count(Object value) {
        if (value == null) return new Count(null, false);
        if (!(value instanceof Number)) return new Count(null, true);
        try {
            long parsed = new BigDecimal(value.toString()).longValueExact();
            return parsed < 0 ? new Count(null, true) : new Count(parsed, false);
        } catch (NumberFormatException | ArithmeticException ignored) { return new Count(null, true); }
    }
    private static boolean negative(Long value) { return value != null && value < 0; }
}