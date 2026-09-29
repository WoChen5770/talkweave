package io.github.wochen5770.talkweave.managed.binding;

import io.github.wochen5770.talkweave.channel.wechat.*;
import io.github.wochen5770.talkweave.managed.persistence.*;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedUsers.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Network outside locks/SQL. Each result is rechecked against its immutable attempt ID and epoch. */
public final class BindingCoordinator implements AutoCloseable {
    public interface Port extends AutoCloseable {
        WechatApiClient.QrCode request();
        WechatApiClient.LoginStatus poll(URI origin, WechatApiClient.QrCode qr, String pairingCode);
        void validate(WechatApiClient.Credentials credentials);
        @Override void close();
    }
    @FunctionalInterface public interface Ports { Port open(); }
    public record Status(String id, String userId, long authEpoch, Mode mode, Phase phase, long expiresAt, boolean imageReady) { }
    private static final Set<Phase> OPEN = Set.of(Phase.REQUESTING_QR, Phase.QR_READY, Phase.SCANNED, Phase.NEED_PAIRING, Phase.VERIFYING_IDENTITY);
    private final ManagedUsers users;
    private final BindingMaterials materials;
    private final ScannerIdentityResolver resolver;
    private final Ports ports;
    private final Clock clock;
    private final Consumer<ManagedScope> activated;
    private final int capacity;
    private final Map<String, Job> jobs = new HashMap<>();
    private final ThreadPoolExecutor network;
    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("binding-ticks").factory());
    private boolean closed;
    private boolean started;
    private static final class Job {
        final Attempt initial;
        final Port port;
        WechatApiClient.QrCode qr;
        URI origin = WechatApiClient.LOGIN_ORIGIN;
        String code;
        Future<?> future;
        Thread executingThread;
        boolean busy;
        boolean disposed;
        boolean imageReady;
        int redirects;
        Job(Attempt initial, Port port) { this.initial = initial; this.port = port; }
    }
    public BindingCoordinator(ManagedUsers users, BindingMaterials materials, ScannerIdentityResolver resolver,
                              Ports ports, Clock clock, Consumer<ManagedScope> activated, int capacity) {
        if (capacity < 1 || capacity > 1000) throw new IllegalArgumentException("Invalid binding task capacity");
        this.users = users; this.materials = materials; this.resolver = resolver; this.ports = ports;
        this.clock = clock; this.activated = activated; this.capacity = capacity;
        this.network = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                Thread.ofVirtual().name("binding-network-", 0).factory());
    }
    public static Ports wechatPorts(Set<String> hosts) {
        return () -> {
            var client = new WechatApiClient(Duration.ofSeconds(15), Duration.ofSeconds(35), hosts);
            return new Port() {
                @Override public WechatApiClient.QrCode request() { return client.requestQr(List.of()); }
                @Override public WechatApiClient.LoginStatus poll(URI origin, WechatApiClient.QrCode qr, String code) { return client.pollQr(origin, qr, code); }
                @Override public void validate(WechatApiClient.Credentials credentials) { client.validateCredentials(credentials); }
                @Override public void close() { client.close(); }
            };
        };
    }
    public synchronized void start() {
        if (closed || started) return;
        started = true;
        ticks.scheduleWithFixedDelay(() -> { try { tick(); } catch (RuntimeException ignored) { /* No secrets in logs; next tick rechecks storage. */ } }, 0, 1, TimeUnit.SECONDS);
    }
    public synchronized Status create(String userId, Mode mode, long epoch, String previousAttempt, boolean confirmReplacement) {
        if (closed) throw new ManagedProblem(CONFLICT);
        if (mode == Mode.REPLACE && !confirmReplacement) throw new ManagedProblem(INVALID_INPUT);
        // Count queued/in-flight tasks globally, including different users. No unbounded executor submissions.
        sweep();
        long other = jobs.values().stream().filter(j -> !j.initial.userId().equals(userId)).count();
        if (other >= capacity) throw new ManagedProblem(BACKLOG);
        var attempt = users.beginChecked(userId, mode, epoch, previousAttempt);
        for (Job old : List.copyOf(jobs.values())) if (old.initial.userId().equals(userId)) dispose(old);
        try {
            materials.create(attempt.id());
            Job job = new Job(attempt, ports.open()); jobs.put(attempt.id(), job);
            return status(attempt, false);
        } catch (RuntimeException failure) {
            users.endAttempt(userId, attempt.id(), Phase.FAILED); materials.clear(attempt.id()); throw failure;
        }
    }
    public synchronized Status current(String userId) {
        sweep(); return users.latestAttempt(userId).map(a -> status(a, ready(a))).orElse(null);
    }
    public synchronized Status get(String userId, String id) {
        sweep(); var attempt = users.attempt(userId, id); return status(attempt, ready(attempt));
    }
    public synchronized byte[] image(String userId, String id) {
        sweep(); var attempt = users.checkAttempt(userId, id);
        if (!ready(attempt)) throw new ManagedProblem(NOT_FOUND);
        return materials.image(id);
    }
    public synchronized Status cancel(String userId, String id) {
        users.cancel(userId, id);
        Job job = jobs.get(id); if (job != null) dispose(job);
        return status(users.attempt(userId, id), false);
    }
    public synchronized Status pair(String userId, String id, String code) {
        if (code == null || !code.matches("[0-9]{1,16}")) throw new ManagedProblem(INVALID_INPUT);
        var attempt = users.checkAttempt(userId, id); Job job = jobs.get(id);
        if (job == null || attempt.phase() != Phase.NEED_PAIRING || job.code != null || job.busy) throw new ManagedProblem(CONFLICT);
        // Leave NEED_PAIRING while this code is being checked. A repeated challenge can then request a new code.
        var submitting = users.advance(userId, id, Phase.NEED_PAIRING, Phase.SCANNED);
        job.code = code; return status(submitting, job.imageReady);
    }
    /** Nonblocking tick, also exposed for deterministic synthetic tests. */
    public synchronized void tick() {
        if (closed) return;
        sweep();
        for (Job job : List.copyOf(jobs.values())) {
            if (job.busy) continue;
            var current = users.checkAttempt(job.initial.userId(), job.initial.id());
            if (current.phase() == Phase.NEED_PAIRING && job.code == null) continue;
            String code = job.code; job.code = null; job.busy = true;
            try { job.future = network.submit(() -> exchange(job, code)); }
            catch (RejectedExecutionException busy) { job.busy = false; job.code = code; }
        }
    }
    private void exchange(Job job, String code) {
        ManagedScope installed = null;
        try {
            WechatApiClient.QrCode qr;
            URI origin;
            synchronized (this) { if (!live(job)) return; job.executingThread = Thread.currentThread(); qr = job.qr; origin = job.origin; }
            if (qr == null) {
                var requested = job.port.request();
                synchronized (this) {
                    if (!live(job)) return;
                    materials.writeQr(job.initial.id(), requested.displayContent());
                    users.advance(job.initial.userId(), job.initial.id(), Phase.REQUESTING_QR, Phase.QR_READY);
                    job.qr = requested; job.imageReady = true;
                }
            } else {
                var response = job.port.poll(origin, qr, code);
                synchronized (this) {
                    if (!live(job)) return;
                    var attempt = users.checkAttempt(job.initial.userId(), job.initial.id());
                    switch (response.phase()) {
                        case WAIT -> advance(job, attempt.phase(), Phase.QR_READY);
                        case SCANNED -> advance(job, attempt.phase(), Phase.SCANNED);
                        case NEED_VERIFY_CODE -> advance(job, attempt.phase(), Phase.NEED_PAIRING);
                        case REDIRECT -> {
                            if (++job.redirects > 5 || response.redirect() == null) finish(job, Phase.FAILED);
                            else job.origin = response.redirect(); // The production Port validates the exact approved origin before HTTP.
                        }
                        case EXPIRED -> finish(job, Phase.EXPIRED);
                        case VERIFY_CODE_BLOCKED, BOUND_REDIRECT -> finish(job, Phase.FAILED);
                        case CONFIRMED -> {
                            job.port.validate(response.credentials());
                            advance(job, attempt.phase(), Phase.VERIFYING_IDENTITY);
                            var identity = resolver.resolve(new ScannerIdentityResolver.LoginEvidence(response, WechatApiClient.CHANNEL_VERSION));
                            if (identity instanceof ScannerIdentityResolver.VerifiedIdentity verified) {
                                installed = users.activate(job.initial.userId(), job.initial.id(), verified, response.credentials());
                                dispose(job);
                            } else finish(job, Phase.IDENTITY_UNVERIFIED);
                        }
                    }
                }
            }
        } catch (ManagedProblem failure) {
            synchronized (this) {
                if (!job.disposed) finish(job, failure.code() == EXPIRED ? Phase.EXPIRED : failure.code() == CONFLICT ? Phase.CONFLICT : Phase.FAILED);
            }
        } catch (RuntimeException failure) {
            synchronized (this) { if (!job.disposed) finish(job, Phase.FAILED); }
        } finally {
            synchronized (this) { job.busy = false; job.executingThread = null; }
        }
        // Activation has committed. Runtime startup failures must not turn it into an invitation again.
        if (installed != null) activated.accept(installed);
    }
    private void advance(Job job, Phase expected, Phase next) {
        if (expected != next) users.advance(job.initial.userId(), job.initial.id(), expected, next);
    }
    private boolean live(Job job) {
        if (closed || job.disposed || jobs.get(job.initial.id()) != job) return false;
        users.checkAttempt(job.initial.userId(), job.initial.id()); return true;
    }
    private boolean ready(Attempt attempt) {
        Job job = jobs.get(attempt.id()); return OPEN.contains(attempt.phase()) && job != null && job.imageReady && !job.disposed;
    }
    private void sweep() {
        for (Job job : List.copyOf(jobs.values())) {
            Attempt a = users.attempt(job.initial.userId(), job.initial.id());
            if (!OPEN.contains(a.phase())) dispose(job);
            else if (clock.millis() >= a.expiresAt()) finish(job, Phase.EXPIRED);
            else {
                try { users.checkAttempt(a.userId(), a.id()); }
                catch (ManagedProblem failure) {
                    if (failure.code() == UNAUTHORIZED || failure.code() == CONFLICT) finish(job, Phase.CANCELLED);
                    else throw failure;
                }
            }
        }
    }
    private void finish(Job job, Phase phase) {
        try { users.endAttempt(job.initial.userId(), job.initial.id(), phase); }
        finally { dispose(job); }
    }
    private void dispose(Job job) {
        if (job.disposed) return;
        job.disposed = true; jobs.remove(job.initial.id()); job.code = null; job.qr = null; job.imageReady = false;
        if (job.future != null && !job.future.isDone()) { job.future.cancel(job.executingThread != Thread.currentThread()); network.purge(); }
        try { job.port.close(); } finally { materials.clear(job.initial.id()); }
    }
    private static Status status(Attempt a, boolean ready) { return new Status(a.id(), a.userId(), a.authEpoch(), a.mode(), a.phase(), a.expiresAt(), ready); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; ticks.shutdownNow();
        try { for (Job job : List.copyOf(jobs.values())) finish(job, Phase.CANCELLED); }
        finally { network.shutdownNow(); }
    }
}
