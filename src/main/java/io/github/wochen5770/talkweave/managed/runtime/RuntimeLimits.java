package io.github.wochen5770.talkweave.managed.runtime;

/** Server-side resource limits, not end-user quotas or model configuration. */
public record RuntimeLimits(int concurrency, int perUserBacklog, int totalBacklog, int maxConnections,
                            long minFreeBytes, int replyBytes) {
    public RuntimeLimits {
        if (concurrency < 1 || concurrency > 64 || perUserBacklog < 1 || totalBacklog < perUserBacklog
                || totalBacklog > 1_000_000 || maxConnections < 1 || maxConnections > 1000
                || minFreeBytes < 0 || replyBytes < 128 || replyBytes > 100_000)
            throw new IllegalArgumentException("Invalid managed runtime limits");
    }
    public static RuntimeLimits defaults() { return new RuntimeLimits(4, 1000, 10000, 100, 104857600, 4096); }
}
