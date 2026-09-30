package io.github.wochen5770.talkweave.managed.cache;

import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import io.github.wochen5770.talkweave.managed.config.ExternalServices;
import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.managed.persistence.ManagedConversations.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Cache errors are optional-performance failures; DB authorization/reads always propagate. */
public final class HistoryService implements AutoCloseable {
    public record Status(String state, long hit, long miss, long stale, long error, long bypass,
                         long bodyQueries, long reads, long totalReadNanos) { }
    private record Flight(HistorySnapshot snapshot, long version, int rounds) { }
    private record Prepared(String key, HistoryWindow window) { }
    private final ManagedConversations conversations;
    private final ExternalServices.HistoryCache config;
    private final HistoryCachePort cache;
    private final String namespace;
    private final LongAdder hit = new LongAdder(), miss = new LongAdder(), stale = new LongAdder(), error = new LongAdder(), bypass = new LongAdder();
    private final LongAdder bodyQueries = new LongAdder(), reads = new LongAdder(), nanos = new LongAdder();
    private final ConcurrentMap<Flight, CompletableFuture<HistoryRead>> flights = new ConcurrentHashMap<>();
    private final Semaphore rebuilds;
    private final Map<Long, Prepared> prepared = new LinkedHashMap<>();
    private volatile boolean degraded;
    private volatile boolean closed;

    public HistoryService(ManagedConversations conversations, ExternalServices.HistoryCache config,
                          HistoryCachePort cache, String installationId, String runEpoch) {
        this.conversations = Objects.requireNonNull(conversations); this.config = Objects.requireNonNull(config);
        this.cache = cache; rebuilds = new Semaphore(config.maxConcurrent());
        namespace = config.keyPrefix() + ":" + UUID.fromString(installationId) + ":" + UUID.fromString(runEpoch) + ":history:v1:";
    }
    public String key(HistorySnapshot snapshot, long modelVersion) {
        return namespace + UUID.fromString(snapshot.scope().userId()) + ":" + UUID.fromString(snapshot.scope().bindingId()) + ":"
                + UUID.fromString(snapshot.conversationId()) + ":" + modelVersion;
    }
    public List<DialogueMessage> load(ManagedScope scope, long sequence, ManagedSettings.ModelSnapshot model) {
        long start = System.nanoTime(); reads.increment();
        try {
            int rounds = model.configuration().historyRounds();
            if (rounds == 0) {
                conversations.historySnapshot(scope, sequence); // authorize, but no historical bodies or Redis
                return List.of();
            }
            if (!enabled()) { bypass.increment(); return source(scope, sequence, rounds).messages(); }
            var snapshot = conversations.historySnapshot(scope, sequence);
            if (!snapshot.latestTail()) { bypass.increment(); return source(scope, sequence, rounds).messages(); }
            String key = key(snapshot, model.version());
            long deadline = System.nanoTime() + config.stageBudget().toNanos();
            HistoryWindow window = null;
            try {
                String raw = cache.get(key, deadline);
                if (raw == null) miss.increment();
                else {
                    try { window = HistoryWindow.decode(raw, config.maxEntryBytes()); }
                    catch (IllegalArgumentException corrupt) {
                        cache.discard(key, raw, deadline); throw corrupt;
                    }
                    if (!window.matches(snapshot, model.version(), rounds)) {
                        stale.increment(); window = null;
                        cache.discard(key, raw, deadline);
                    }
                }
                degraded = false;
            } catch (RuntimeException unavailable) { failed(); }
            if (window != null) {
                hit.increment(); remember(sequence, key, window);
                return window.read(snapshot, rounds).messages();
            }
            var read = rebuild(new Flight(snapshot, model.version(), rounds), deadline);
            // A historical slice cannot replace a current tail; oversized complete windows are simply not cached.
            try {
                var candidate = HistoryWindow.from(read, model.version(), rounds);
                String encoded = candidate.encode(config.maxEntryBytes());
                remember(sequence, key, candidate);
                if (System.nanoTime() < deadline) {
                    try { cache.put(key, encoded, deadline); degraded = false; }
                    catch (RuntimeException unavailable) { failed(); }
                } else bypass.increment();
            } catch (IllegalArgumentException uncacheable) { bypass.increment(); }
            return read.messages();
        } finally { nanos.add(System.nanoTime() - start); }
    }
    private HistoryRead source(ManagedScope scope, long sequence, int rounds) {
        bodyQueries.increment(); return conversations.readHistory(scope, sequence, rounds);
    }
    private HistoryRead rebuild(Flight flight, long deadline) {
        // Coalesce only identical authorized snapshots. No user-provided keys, unbounded queue, or background tasks.
        var existing = flights.get(flight);
        if (existing != null) {
            try { return existing.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ManagedProblem(ManagedProblem.Code.DATABASE_UNAVAILABLE); }
            catch (ExecutionException failed) { throw new ManagedProblem(ManagedProblem.Code.DATABASE_UNAVAILABLE); }
            catch (TimeoutException slow) { bypass.increment(); }
        }
        if (!rebuilds.tryAcquire()) return source(flight.snapshot().scope(), flight.snapshot().beforeSequence(), flight.rounds());
        var future = new CompletableFuture<HistoryRead>();
        var prior = flights.putIfAbsent(flight, future);
        try {
            if (prior != null) return source(flight.snapshot().scope(), flight.snapshot().beforeSequence(), flight.rounds());
            var read = source(flight.snapshot().scope(), flight.snapshot().beforeSequence(), flight.rounds());
            future.complete(read); return read;
        } catch (RuntimeException failed) { future.completeExceptionally(failed); throw failed; }
        finally { if (prior == null) flights.remove(flight, future); rebuilds.release(); }
    }
    private synchronized void remember(long sequence, String key, HistoryWindow window) {
        if (closed) return;
        prepared.put(sequence, new Prepared(key, window));
        while (prepared.size() > config.requestQueueSize()) prepared.remove(prepared.keySet().iterator().next());
    }
    /** The caller has already committed delivery. Never propagate cache failure into a send retry. */
    public void afterSend(long sequence, Optional<Delivered> delivered) {
        Prepared previous;
        synchronized (this) { previous = prepared.remove(sequence); }
        if (!enabled() || previous == null || delivered.isEmpty()) return;
        try {
            var next = previous.window().append(delivered.get());
            if (next.isEmpty()) { stale.increment(); return; }
            cache.put(previous.key(), next.get().encode(config.maxEntryBytes()), System.nanoTime() + config.stageBudget().toNanos());
            degraded = false;
        } catch (RuntimeException unavailable) { failed(); }
    }
    private boolean enabled() { return !closed && config.enabled() && cache != null; }
    private void failed() { error.increment(); degraded = true; }
    public Status status() {
        return new Status(!enabled() ? "DISABLED" : degraded ? "DEGRADED" : "AVAILABLE", hit.sum(), miss.sum(), stale.sum(), error.sum(), bypass.sum(),
                bodyQueries.sum(), reads.sum(), nanos.sum());
    }
    @Override public void close() {
        closed = true;
        synchronized (this) { prepared.clear(); }
        if (cache != null) cache.close();
    }
}
