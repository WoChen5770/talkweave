package io.github.wochen5770.talkweave.managed.cache;

/** A deadline applies to connection acquisition and commands together, outside JDBC transactions. */
public interface HistoryCachePort extends AutoCloseable {
    /** A bounded admission/cooldown refusal, distinct from an attempted command failure. */
    final class Bypassed extends RuntimeException {
        public Bypassed() { super("History cache bypassed"); }
    }
    String get(String key, long deadlineNanos);
    void put(String key, String value, long deadlineNanos);
    void discard(String key, String expectedValue, long deadlineNanos);
    @Override void close();
}
