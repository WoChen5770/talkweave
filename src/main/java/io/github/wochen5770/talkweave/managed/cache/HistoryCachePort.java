package io.github.wochen5770.talkweave.managed.cache;

/** A deadline applies to connection acquisition and commands together, outside JDBC transactions. */
public interface HistoryCachePort extends AutoCloseable {
    String get(String key, long deadlineNanos);
    void put(String key, String value, long deadlineNanos);
    void discard(String key, String expectedValue, long deadlineNanos);
    @Override void close();
}
