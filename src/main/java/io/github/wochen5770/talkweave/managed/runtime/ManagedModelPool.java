package io.github.wochen5770.talkweave.managed.runtime;

import io.github.wochen5770.talkweave.assistant.AssistantService;
import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import io.github.wochen5770.talkweave.managed.persistence.ManagedSettings;
import io.github.wochen5770.talkweave.model.*;
import java.util.*;

/** Versioned shared clients. A retired configuration stays open until its last in-flight lease returns. */
public final class ManagedModelPool implements ManagedTurnWorker.ModelCall, AutoCloseable {
    interface Client extends AutoCloseable {
        AssistantService.Reply answer(ModelRequestContext context, List<DialogueMessage> messages, ModelAttemptObserver observer);
        @Override void close();
    }
    @FunctionalInterface interface Clients { Client open(ManagedSettings.ModelSnapshot snapshot); }
    private final Clients factory;
    public ManagedModelPool() {
        this(snapshot -> {
            var delegate = new CompatibleChatClient(snapshot.configuration(), snapshot.version());
            return new Client() {
                public AssistantService.Reply answer(ModelRequestContext context, List<DialogueMessage> messages, ModelAttemptObserver observer) { return delegate.answer(context, messages, observer); }
                public void close() { delegate.close(); }
            };
        });
    }
    ManagedModelPool(Clients factory) { this.factory = factory; }
    private static final class Entry {
        final Client client;
        int leases;
        Entry(Client client) { this.client = client; }
    }
    private final Map<Long, Entry> entries = new HashMap<>();
    private long latest;
    private boolean closed;
    @Override public AssistantService.Reply answer(ManagedSettings.ModelSnapshot snapshot, ModelRequestContext context,
                                                   List<DialogueMessage> messages, ModelAttemptObserver observer) {
        Entry entry;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Model pool closed");
            latest = Math.max(latest, snapshot.version());
            entry = entries.computeIfAbsent(snapshot.version(), ignored -> new Entry(factory.open(snapshot))); entry.leases++;
            retire();
        }
        try { return entry.client.answer(context, messages, observer); }
        finally { synchronized (this) { entry.leases--; retire(); } }
    }
    private void retire() {
        var it = entries.entrySet().iterator();
        while (it.hasNext()) { var item = it.next(); if (item.getValue().leases == 0 && (closed || item.getKey() != latest)) { item.getValue().client.close(); it.remove(); } }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var entry : entries.values()) entry.client.close();
        entries.clear();
    }
}
