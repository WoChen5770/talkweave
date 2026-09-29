package io.github.wochen5770.talkweave.runtime;

/** Deterministic capped backoff; sleeping/cancellation belongs to the lifecycle owner. */
public final class Backoff {
    private int failures;
    public long failureDelayMillis() { return Math.min(30_000L, 1000L << Math.min(failures++, 5)); }
    public void reset() { failures = 0; }
}