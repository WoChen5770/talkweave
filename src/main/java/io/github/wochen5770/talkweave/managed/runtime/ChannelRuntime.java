package io.github.wochen5770.talkweave.managed.runtime;

import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.*;
import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** A credential generation owns exactly one HTTP client, receive loop and scheduler registration. */
public final class ChannelRuntime implements AutoCloseable {
    public interface Port extends AutoCloseable {
        void validate(Credentials credentials);
        Updates updates(Credentials credentials, String cursor);
        void lifecycle(Credentials credentials, boolean starting);
        String typingTicket(Credentials credentials, String recipient, String context);
        void typing(Credentials credentials, String recipient, String ticket, boolean typing);
        void send(Credentials credentials, String recipient, String context, String clientId, String text);
        @Override void close();
    }
    @FunctionalInterface public interface Ports { Port open(); }
    public enum State { STARTING, CONNECTED, RECONNECTING, USER_BACKLOG, GLOBAL_BACKLOG, STORAGE_PAUSED, REAUTH_REQUIRED, FAULTED, STOPPED }
    public record Status(State state, String error) { }
    private final ManagedScope scope;
    private final ManagedUsers users;
    private final ManagedConversations conversations;
    private final Port port;
    private final BooleanSupplier receiveAllowed;
    private final FairUserScheduler.Registration registration;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean halted;
    private volatile State state = State.STARTING;
    private volatile String error;
    private volatile String workError;
    private volatile Thread receiver;

    public ChannelRuntime(ManagedScope scope, ManagedUsers users, ManagedSettings settings, ManagedConversations conversations,
                          ManagedUsage usage, FairUserScheduler scheduler, ManagedTurnWorker.ModelCall model,
                          Port port, BooleanSupplier receiveAllowed, int replyBytes) {
        this.scope = scope; this.users = users; this.conversations = conversations; this.port = port; this.receiveAllowed = receiveAllowed;
        var worker = new ManagedTurnWorker(users, settings, conversations, usage,
                (snapshot, context, messages, observer) -> {
                    authorized();
                    try { var reply = model.answer(snapshot, context, messages, observer); workError = null; return reply; }
                    catch (RemoteFailure failure) { workError = "MODEL_" + failure.kind().name(); throw failure; }
                }, (ignored, recipient, context, clientId, text) -> {
                    var current = authorized();
                    try { port.send(current.credentials(), recipient, context, clientId, text); }
                    catch (RemoteFailure failure) { remote(failure); throw failure; }
                }, replyBytes, this::typingActivity);
        registration = scheduler.register(scope.userId(), () -> {
            if (closed.get() || halted) return false;
            return worker.runOnce(scope);
        }, this::workerFailed);
    }
    public static Ports wechatPorts(Set<String> hosts) {
        return () -> {
            var client = new WechatApiClient(Duration.ofSeconds(15), Duration.ofSeconds(35), hosts);
            return new Port() {
                public void validate(Credentials c) { client.validateCredentials(c); }
                public Updates updates(Credentials c, String cursor) { return client.getUpdates(c, cursor); }
                public void lifecycle(Credentials c, boolean start) { client.notifyLifecycle(c, start); }
                public String typingTicket(Credentials c, String recipient, String context) { return client.getTypingTicket(c, recipient, context); }
                public void typing(Credentials c, String recipient, String ticket, boolean typing) { client.sendTyping(c, recipient, ticket, typing); }
                public void send(Credentials c, String recipient, String context, String id, String text) { client.sendText(c, recipient, context, id, text); }
                public void close() { client.close(); }
            };
        };
    }
    public ManagedScope scope() { return scope; }
    public Status status() { return new Status(state, error != null ? error : workError); }
    public synchronized void start() {
        if (closed.get() || receiver != null) return;
        receiver = Thread.ofVirtual().name("managed-receiver").unstarted(this::receive);
        receiver.start();
    }
    private ManagedUsers.ConnectionState authorized() {
        if (closed.get() || halted || Thread.currentThread().isInterrupted()) throw new ManagedProblem(ManagedProblem.Code.UNAUTHORIZED);
        return users.connection(scope);
    }
    private void receive() {
        Updates pending = null;
        int failures = 0;
        boolean initialized = false;
        try {
            registration.wake(); // Drain durable received/response-ready work restored from a previous process.
            while (!closed.get() && !halted) {
                try {
                    var connection = authorized();
                    if (!receiveAllowed.getAsBoolean()) { state = State.STORAGE_PAUSED; error = "STORAGE_UNAVAILABLE"; pause(500); continue; }
                    var capacity = conversations.capacity(scope);
                    if (capacity != ManagedConversations.Capacity.AVAILABLE) {
                        state = capacity == ManagedConversations.Capacity.USER_BACKLOG ? State.USER_BACKLOG : State.GLOBAL_BACKLOG;
                        error = state.name(); registration.wake(); pause(250); continue;
                    }
                    if (!initialized) {
                        port.validate(connection.credentials()); initialized = true;
                        try { port.lifecycle(authorized().credentials(), true); }
                        catch (RemoteFailure failure) { remote(failure); }
                    }
                    if (pending == null) pending = port.updates(authorized().credentials(), connection.cursor());
                    authorized(); // A late long-poll result cannot enter a successor binding.
                    if (!receiveAllowed.getAsBoolean()) { state = State.STORAGE_PAUSED; error = "STORAGE_UNAVAILABLE"; pause(500); continue; }
                    conversations.accept(scope, pending);
                    pending = null; failures = 0; state = State.CONNECTED; error = null;
                    registration.wake(); pause(50); // A misbehaving empty immediate response must not create a busy loop.
                } catch (ManagedProblem failure) {
                    if (failure.code() == ManagedProblem.Code.UNAUTHORIZED) break;
                    if (failure.code() == ManagedProblem.Code.BACKLOG) { state = State.USER_BACKLOG; error = "BATCH_BACKLOG"; registration.wake(); pause(250); }
                    else { state = State.STORAGE_PAUSED; error = "DATABASE_UNAVAILABLE"; pause(1000); }
                } catch (RemoteFailure failure) {
                    remote(failure);
                    if (!halted) { state = State.RECONNECTING; pause(Math.min(30_000, 250L << Math.min(failures++, 7))); }
                }
            }
        } catch (ManagedProblem failure) {
            if (!closed.get()) { state = State.FAULTED; error = failure.code().name(); }
        } catch (RemoteFailure failure) {
            remote(failure); if (!halted) { state = State.FAULTED; halted = true; }
        } catch (RuntimeException failure) { state = State.FAULTED; error = "CHANNEL_FAILURE"; halted = true; }
        finally {
            pending = null;
            if (!closed.get() && !halted) state = State.STOPPED;
            if (halted && !closed.get()) { registration.close(); port.close(); }
        }
    }
    private Runnable typingActivity(ManagedConversations.Event event) {
        String ticket;
        try {
            ticket = port.typingTicket(authorized().credentials(), scope.senderId(), event.contextToken());
            if (ticket == null || ticket.isBlank()) return () -> { };
            port.typing(authorized().credentials(), scope.senderId(), ticket, true);
        } catch (RemoteFailure failure) { remote(failure); return () -> { }; }
        return () -> {
            try { port.typing(authorized().credentials(), scope.senderId(), ticket, false); }
            catch (ManagedProblem failure) { /* Revoked scopes cannot make even a stop-typing call. */ }
            catch (RemoteFailure failure) { remote(failure); }
        };
    }
    private void remote(RemoteFailure failure) {
        error = "WECHAT_" + failure.kind().name();
        if (failure.kind() == RemoteFailure.Kind.STALE_TOKEN || failure.kind() == RemoteFailure.Kind.UNSAFE_ENDPOINT) {
            halted = true; state = State.REAUTH_REQUIRED;
            users.invalidateSession(scope);
            registration.close();
        }
    }
    private void workerFailed() {
        if (!closed.get()) { halted = true; state = State.FAULTED; error = "WORK_INTERRUPTED"; }
        try { conversations.interrupt(scope); } catch (ManagedProblem ignored) { error = "DATABASE_UNAVAILABLE"; }
        var thread = receiver; if (thread != null) thread.interrupt();
        // Only this connection is released; never close the shared scheduler or model pool here.
        port.close();
    }
    private void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); halted = true; }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        state = State.STOPPED; registration.close();
        var thread = receiver; if (thread != null) thread.interrupt();
        try { port.close(); }
        finally { conversations.interrupt(scope); }
    }
}
