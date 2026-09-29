package io.github.wochen5770.talkweave.managed.runtime;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** One queued ticket per user, one step per round. Durable event limits belong to the repository. */
public final class FairUserScheduler implements AutoCloseable {
    public interface Registration extends AutoCloseable {
        void wake();
        @Override void close();
    }
    private final int concurrency;
    private final int userLimit;
    private final ExecutorService workers;
    private final Map<String, Slot> slots = new HashMap<>();
    private final ArrayDeque<Slot> ready = new ArrayDeque<>();
    private int running;
    private boolean closed;
    public FairUserScheduler() { this(4, 10_000); }
    public FairUserScheduler(int concurrency, int userLimit) {
        if (concurrency < 1 || concurrency > 64 || userLimit < 1) throw new IllegalArgumentException("Invalid scheduler limits");
        this.concurrency = concurrency; this.userLimit = userLimit;
        workers = Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().name("managed-worker-", 0).factory());
    }
    public synchronized Registration register(String userId, BooleanSupplier step, Runnable onFailure) {
        if (closed) throw new IllegalStateException("Scheduler closed");
        if (userId == null || userId.isBlank() || step == null || onFailure == null) throw new IllegalArgumentException("Invalid user worker");
        if (slots.containsKey(userId) || slots.size() >= userLimit) throw new IllegalStateException("User worker conflict or capacity reached");
        var slot = new Slot(userId, step, onFailure); slots.put(userId, slot); return slot;
    }
    public synchronized boolean registered(String userId) { return slots.containsKey(userId); }
    private final class Slot implements Registration {
        final String userId;
        final BooleanSupplier step;
        final Runnable onFailure;
        boolean live = true;
        boolean queued;
        boolean active;
        boolean notified;
        Thread thread;
        Slot(String userId, BooleanSupplier step, Runnable onFailure) { this.userId = userId; this.step = step; this.onFailure = onFailure; }
        @Override public void wake() {
            synchronized (FairUserScheduler.this) {
                if (!live || closed) return;
                if (active) notified = true;
                else enqueue(this);
                pump();
            }
        }
        @Override public void close() {
            synchronized (FairUserScheduler.this) {
                live = false; ready.remove(this); queued = false;
                // Keep the slot reserved until the old worker actually exits; prevents an ABA overlap.
                if (!active) slots.remove(userId, this);
                if (thread != null && thread != Thread.currentThread()) thread.interrupt();
            }
        }
    }
    private void enqueue(Slot slot) {
        if (slot.live && !slot.queued && !slot.active) { slot.queued = true; ready.addLast(slot); }
    }
    private void pump() {
        while (!closed && running < concurrency && !ready.isEmpty()) {
            Slot slot = ready.removeFirst(); slot.queued = false;
            if (!slot.live) continue;
            slot.active = true; running++;
            workers.execute(() -> execute(slot));
        }
    }
    private void execute(Slot slot) {
        boolean more = false;
        boolean failed = false;
        try {
            synchronized (this) {
                slot.thread = Thread.currentThread();
                if (!slot.live || closed) return;
            }
            more = slot.step.getAsBoolean();
        } catch (RuntimeException failure) {
            failed = true;
            // No throwable/message is sent to logs, nor is the failed step automatically replayed.
            try { slot.onFailure.run(); } catch (RuntimeException ignored) { }
        } finally {
            synchronized (this) {
                slot.thread = null; slot.active = false; running--;
                if (failed) slot.live = false;
                if (!slot.live || closed) slots.remove(slot.userId, slot);
                else if (more || slot.notified) enqueue(slot);
                slot.notified = false;
                pump(); notifyAll();
            }
        }
    }
    public boolean stop(Duration budget) {
        if (budget == null || budget.isNegative() || budget.compareTo(Duration.ofSeconds(8)) > 0)
            throw new IllegalArgumentException("Shutdown budget must be between zero and eight seconds");
        synchronized (this) {
            closed = true; ready.clear();
            for (var slot : slots.values()) { slot.live = false; if (slot.thread != null) slot.thread.interrupt(); }
            workers.shutdown();
        }
        try { return workers.awaitTermination(budget.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }
    @Override public void close() { stop(Duration.ofSeconds(4)); }
}
