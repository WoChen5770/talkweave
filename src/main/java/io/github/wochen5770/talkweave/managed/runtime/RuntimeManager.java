package io.github.wochen5770.talkweave.managed.runtime;

import io.github.wochen5770.talkweave.managed.persistence.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Reconciles persisted authorization to independent connections. No network work under this lock. */
public final class RuntimeManager implements AutoCloseable {
    public enum Health { STARTING, RUNNING, DATABASE_UNAVAILABLE, LOW_DISK, GLOBAL_BACKLOG, CONNECTION_LIMIT, STOPPED }
    public record Status(Health health, int activeConnections, int maxConnections) { }
    private final ManagedUsers users;
    private final ManagedSettings settings;
    private final ManagedConversations conversations;
    private final ManagedUsage usage;
    private final RuntimeLimits limits;
    private final ChannelRuntime.Ports ports;
    private final ManagedTurnWorker.ModelCall model;
    private final LongSupplier freeBytes;
    private final FairUserScheduler scheduler;
    private final Map<String, ChannelRuntime> channels = new LinkedHashMap<>();
    private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("managed-runtime-manager").factory());
    private volatile Health health = Health.STARTING;
    private volatile boolean closed;
    private boolean started;

    public RuntimeManager(ManagedUsers users, ManagedSettings settings, ManagedConversations conversations, ManagedUsage usage,
                          RuntimeLimits limits, ChannelRuntime.Ports ports, ManagedTurnWorker.ModelCall model, LongSupplier freeBytes) {
        this.users = users; this.settings = settings; this.conversations = conversations; this.usage = usage;
        this.limits = limits; this.ports = ports; this.model = model; this.freeBytes = freeBytes;
        scheduler = new FairUserScheduler(limits.concurrency(), limits.maxConnections());
    }
    public static LongSupplier diskSpace(Path directory) {
        return () -> { try { return Files.getFileStore(directory).getUsableSpace(); } catch (IOException failure) { return -1; } };
    }
    public synchronized void start() {
        if (closed || started) return;
        started = true;
        coordinator.scheduleWithFixedDelay(this::reconcile, 0, 250, TimeUnit.MILLISECONDS);
    }
    /** Also called after admin mutations and verified activation; never trusts the callback's stale scope. */
    public synchronized void reconcile() {
        if (closed) return;
        try {
            List<ManagedScope> active = users.activeScopes();
            var desired = new LinkedHashMap<String, ManagedScope>();
            for (var scope : active) desired.put(scope.userId(), scope);
            for (var entry : List.copyOf(channels.entrySet())) {
                if (!entry.getValue().scope().equals(desired.get(entry.getKey()))) {
                    channels.remove(entry.getKey()); entry.getValue().close();
                }
            }
            health = freeBytes.getAsLong() < limits.minFreeBytes() ? Health.LOW_DISK : conversations.globalBacklog() ? Health.GLOBAL_BACKLOG : Health.RUNNING;
            for (var scope : desired.values()) {
                if (channels.containsKey(scope.userId()) || scheduler.registered(scope.userId())) continue;
                if (channels.size() >= limits.maxConnections()) { if (health == Health.RUNNING) health = Health.CONNECTION_LIMIT; break; }
                ChannelRuntime.Port port = ports.open();
                try {
                    var runtime = new ChannelRuntime(scope, users, settings, conversations, usage, scheduler, model, port,
                            this::receiveAllowed, limits.replyBytes());
                    channels.put(scope.userId(), runtime); runtime.start();
                } catch (RuntimeException failure) { port.close(); throw failure; }
            }
        } catch (RuntimeException failure) {
            // Preserve running clients; ingestion pauses until DB/resource checks succeed. No sensitive exception text.
            health = Health.DATABASE_UNAVAILABLE;
        }
    }
    private boolean receiveAllowed() {
        return !closed && health != Health.DATABASE_UNAVAILABLE && freeBytes.getAsLong() >= limits.minFreeBytes();
    }
    public synchronized boolean live() { return started && !closed; }
    public synchronized Status status() { return new Status(health, channels.size(), limits.maxConnections()); }
    public synchronized ChannelRuntime.Status userStatus(ManagedUsers.Overview user) {
        if (!user.enabled()) return new ChannelRuntime.Status(ChannelRuntime.State.STOPPED, "USER_DISABLED");
        if (!user.bound()) return new ChannelRuntime.Status(ChannelRuntime.State.STOPPED, "UNBOUND");
        if (!user.sessionActive()) return new ChannelRuntime.Status(ChannelRuntime.State.REAUTH_REQUIRED, "CREDENTIALS_INACTIVE");
        var channel = channels.get(user.id());
        if (channel == null || channel.scope().authEpoch() != user.authEpoch())
            return new ChannelRuntime.Status(ChannelRuntime.State.STARTING, health == Health.CONNECTION_LIMIT ? "CONNECTION_LIMIT" : null);
        return channel.status();
    }
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true; health = Health.STOPPED; coordinator.shutdownNow();
            for (var channel : channels.values()) { try { channel.close(); } catch (RuntimeException ignored) { } }
            channels.clear();
        }
        scheduler.stop(Duration.ofSeconds(4));
    }
}
