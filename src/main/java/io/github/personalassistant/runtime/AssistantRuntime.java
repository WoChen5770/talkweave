package io.github.personalassistant.runtime;

import io.github.personalassistant.assistant.AssistantService;
import io.github.personalassistant.channel.wechat.IdentityVerifier;
import io.github.personalassistant.channel.wechat.InboxReceiver;
import io.github.personalassistant.channel.wechat.LoginCoordinator;
import io.github.personalassistant.conversation.ContextBudget;
import io.github.personalassistant.conversation.ConversationWorker;
import io.github.personalassistant.conversation.ReplyFormatter;
import io.github.personalassistant.persistence.ConversationRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.SmartLifecycle;

/** Two independent blocking loops with bounded shutdown, no public listener and no model health probes. */
public final class AssistantRuntime implements SmartLifecycle {
    private final ConversationRepository repository;
    private final LoginCoordinator login;
    private final IdentityVerifier identity;
    private final InboxReceiver receiver;
    private final ConversationWorker worker;
    private final PrivateStateFiles files;
    private final Runnable cancelRequests;
    private final Duration grace;
    private final HealthServer health;
    private final AtomicReference<String> modelState = new AtomicReference<>("NOT_CALLED");
    private volatile String networkState = "WAITING_LOGIN";
    private volatile boolean running;
    private final java.util.function.Consumer<ConversationRepository.Session> onConnected;
    private final Runnable onStopping;
    private Thread receiverThread;
    private Thread workerThread;

    public AssistantRuntime(ConversationRepository repository, LoginCoordinator login, IdentityVerifier identity,
                            InboxReceiver receiver, AssistantService assistant, ConversationWorker.ReplySender sender,
                            AssistantProperties properties, PrivateStateFiles files, Runnable cancelRequests, int healthPort) throws java.io.IOException {
        this(repository, login, identity, receiver, assistant, sender, properties, files, cancelRequests, healthPort,
                work -> () -> { }, session -> { }, () -> { });
    }
    public AssistantRuntime(ConversationRepository repository, LoginCoordinator login, IdentityVerifier identity,
                            InboxReceiver receiver, AssistantService assistant, ConversationWorker.ReplySender sender,
                            AssistantProperties properties, PrivateStateFiles files, Runnable cancelRequests, int healthPort,
                            ConversationWorker.Activity activity, java.util.function.Consumer<ConversationRepository.Session> onConnected,
                            Runnable onStopping) throws java.io.IOException {
        this.onConnected = onConnected; this.onStopping = onStopping;
        this.repository = repository; this.login = login; this.identity = identity; this.receiver = receiver;
        this.files = files; this.cancelRequests = cancelRequests; this.grace = properties.shutdownGrace();
        AssistantService observed = messages -> {
            try {
                var reply = assistant.answer(messages);
                if (reply == null || reply.text() == null || reply.text().isBlank()) throw new IllegalStateException("No usable model text");
                modelState.set("SUCCEEDED"); return reply;
            }
            catch (RuntimeException failure) { modelState.set("FAILED"); throw failure; }
        };
        worker = new ConversationWorker(repository, observed, sender, new ContextBudget(properties.model()), new ReplyFormatter(properties.wechat().maxReplyBytes()), activity);
        health = new HealthServer(healthPort, this::alive, this::snapshot);
    }
    @Override public synchronized void start() {
        if (running) return;
        running = true;
        receiverThread = new Thread(this::receiveLoop, "wechat-receiver");
        workerThread = new Thread(this::workLoop, "conversation-consumer");
        // Real HTTP calls are cancellable; a misbehaving dependency must not prevent JVM exit.
        receiverThread.setDaemon(true); workerThread.setDaemon(true);
        health.start(); receiverThread.start(); workerThread.start();
    }
    private void receiveLoop() {
        var backoff = new Backoff();
        while (running) {
            long delay = 1000;
            try {
                if (login.tick()) {
                    var session = repository.session().orElseThrow();
                    identity.prepare(session);
                    onConnected.accept(session);
                    var result = receiver.receiveOnce(() -> running);
                    networkState = "CONNECTED";
                    delay = result == InboxReceiver.Result.SAVED ? 100 : 1000;
                } else { networkState = login.phase(); identity.clear(); }
                backoff.reset();
                files.writeJson("health.json", snapshot());
            } catch (Exception failure) {
                networkState = failure instanceof RemoteFailure ? "RECONNECTING" : "LOCAL_ERROR";
                delay = backoff.failureDelayMillis();
                diagnostic("RECEIVER", failure);
            }
            if (!sleep(delay)) return;
        }
    }
    private void workLoop() {
        var backoff = new Backoff();
        while (running) {
            long delay;
            try { delay = worker.step() ? 0 : 200; backoff.reset(); }
            catch (Exception failure) { delay = backoff.failureDelayMillis(); diagnostic("CONSUMER", failure); }
            if (!sleep(delay)) return;
        }
    }
    private boolean sleep(long millis) {
        if (!running) return false;
        try { if (millis > 0) Thread.sleep(millis); return running; }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return false; }
    }
    private static void diagnostic(String component, Exception failure) {
        String kind = failure instanceof RemoteFailure remote ? remote.kind().name() : "LOCAL_OPERATION";
        org.slf4j.LoggerFactory.getLogger(AssistantRuntime.class).warn("component={} category={}", component, kind);
    }
    public boolean alive() { return running && receiverThread != null && workerThread != null && receiverThread.isAlive() && workerThread.isAlive(); }
    public Map<String, Object> snapshot() {
        var state = new LinkedHashMap<String, Object>();
        var session = repository.session().orElse(null);
        boolean bound = repository.isBound(session);
        var intake = repository.intakeState();
        state.put("live", alive()); state.put("connection", networkState); state.put("login", login.phase());
        state.put("bound", bound); state.put("model", modelState.get()); state.put("intake", intake.name());
        state.put("pending", repository.pendingCount()); state.put("uncertain", repository.uncertainCount());
        state.put("ready", alive() && "CONNECTED".equals(networkState) && bound && !"FAILED".equals(modelState.get())
                && intake == ConversationRepository.Intake.READY);
        return Map.copyOf(state);
    }
    @Override public synchronized void stop() {
        if (!running) { health.close(); return; }
        long deadline = System.nanoTime() + grace.toNanos();
        running = false;
        worker.stopAccepting();
        login.cancel();
        receiverThread.interrupt();
        Thread stopNotice = Thread.ofVirtual().name("wechat-stop-notice").start(() -> {
            try { onStopping.run(); } catch (RuntimeException ignored) { }
        });
        // Reserve the final 20% for cancellation, durable classification and thread exit.
        await(workerThread, deadline - grace.toNanos() / 5);
        if (workerThread.isAlive()) {
            worker.cancelCurrent();
            workerThread.interrupt();
        }
        await(stopNotice, deadline);
        try { cancelRequests.run(); }
        finally {
            await(workerThread, deadline);
            await(receiverThread, deadline);
            health.close();
            try {
                if (!receiverThread.isAlive()) login.close();
                else files.clearLoginMaterials();
                identity.clear();
                files.writeJson("health.json", Map.of("live", false, "ready", false, "connection", "STOPPED"));
            } catch (java.io.IOException ignored) { }
        }
    }
    private static void await(Thread thread, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || thread == Thread.currentThread()) return;
        try { thread.join(Math.max(1, remaining / 1_000_000)); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    public int healthPort() { return health.port(); }
}