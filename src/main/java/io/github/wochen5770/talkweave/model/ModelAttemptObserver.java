package io.github.wochen5770.talkweave.model;

/** Synchronous durable boundary. Any observer failure aborts the call, never triggering a model retry. */
public interface ModelAttemptObserver {
    enum Outcome { SUCCEEDED, FAILED, UNKNOWN, CANCELLED }
    void beforeAttempt(int number);
    void afterAttempt(int number, Outcome outcome, TokenUsage usage);
    static ModelAttemptObserver noop() {
        return new ModelAttemptObserver() {
            @Override public void beforeAttempt(int number) { }
            @Override public void afterAttempt(int number, Outcome outcome, TokenUsage usage) { }
        };
    }
}
