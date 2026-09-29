package io.github.wochen5770.talkweave.managed.binding;

import io.github.wochen5770.talkweave.channel.wechat.*;
import io.github.wochen5770.talkweave.managed.persistence.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedUsers.*;
import static io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.*;

class BindingCoordinatorTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();
    @Test void independentTasksImagesAndSuccessfulCallbacksNeverCrossUsers() throws Exception {
        try (var f = new Fixture(true)) {
            var a = f.users.create("a"); var b = f.users.create("b");
            var aa = f.create(a); var bb = f.create(b);
            f.ready(aa); f.ready(bb);
            assertThat(f.coordinator.image(a.id(), aa.id())).isNotEqualTo(f.coordinator.image(b.id(), bb.id()));
            rejected(ManagedProblem.Code.NOT_FOUND, () -> f.coordinator.image(a.id(), bb.id()));
            rejected(ManagedProblem.Code.NOT_FOUND, () -> f.coordinator.cancel(a.id(), bb.id()));
            f.ports.get(1).reply = confirmed("b"); f.ports.get(0).reply = confirmed("a");
            f.phase(aa, Phase.SUCCEEDED); f.phase(bb, Phase.SUCCEEDED);
            assertThat(f.users.scope(a.id()).senderId()).isEqualTo("sender-a");
            assertThat(f.users.scope(b.id()).senderId()).isEqualTo("sender-b");
            await(() -> f.activated.size() == 2);
            assertThat(directory.resolve("binding-materials").resolve(aa.id())).doesNotExist();
            assertThat(f.coordinator.get(a.id(), aa.id()).imageReady()).isFalse();
            f.coordinator.tick(); assertThat(f.activated).hasSize(2);
        }
    }
    @Test void productionConfirmedLoginNeverGuessesScannerIdentity() throws Exception {
        try (var f = new Fixture(false)) {
            var a = f.users.create("a"); var task = f.create(a); f.ready(task);
            f.ports.getFirst().reply = confirmed("a"); f.phase(task, Phase.IDENTITY_UNVERIFIED);
            rejected(ManagedProblem.Code.UNAUTHORIZED, () -> f.users.scope(a.id()));
            assertThat(f.activated).isEmpty();
            assertThat(f.coordinator.get(a.id(), task.id()).toString()).doesNotContain("secret-token", "sender-a", "scanner-a");
            assertThat(directory.resolve("binding-materials").resolve(task.id())).doesNotExist();
        }
    }
    @Test void pairingIsScopedSingleUseAndBoundToCurrentTask() throws Exception {
        try (var f = new Fixture(false)) {
            var a = f.users.create("a"); var task = f.create(a); f.ready(task);
            var port = f.ports.getFirst(); port.reply = new LoginStatus(LoginPhase.NEED_VERIFY_CODE, null, null);
            f.phase(task, Phase.NEED_PAIRING); await(() -> !port.polling.get());
            rejected(ManagedProblem.Code.INVALID_INPUT, () -> f.coordinator.pair(a.id(), task.id(), "not-digits"));
            // The poll has completed before phase observation; a short retry may see the worker's finalizer.
            await(() -> { try { f.coordinator.pair(a.id(), task.id(), "123456"); return true; } catch (ManagedProblem busy) { return false; } });
            port.reply = confirmed("a"); f.phase(task, Phase.IDENTITY_UNVERIFIED);
            assertThat(port.codes).containsExactly("123456");
            rejected(ManagedProblem.Code.CONFLICT, () -> f.coordinator.pair(a.id(), task.id(), "654321"));
        }
    }
    @Test void repeatedPairingChallengeAllowsACorrectedCodeWithoutReusingTheOldOne() throws Exception {
        try (var f = new Fixture(false)) {
            var a = f.users.create("a"); var task = f.create(a); f.ready(task);
            var port = f.ports.getFirst(); port.reply = new LoginStatus(LoginPhase.NEED_VERIFY_CODE, null, null);
            f.phase(task, Phase.NEED_PAIRING);
            await(() -> { try { return f.coordinator.pair(a.id(), task.id(), "111111").phase() == Phase.SCANNED; } catch (ManagedProblem busy) { return false; } });
            rejected(ManagedProblem.Code.CONFLICT, () -> f.coordinator.pair(a.id(), task.id(), "222222"));
            f.phase(task, Phase.NEED_PAIRING);
            await(() -> { try { f.coordinator.pair(a.id(), task.id(), "222222"); return true; } catch (ManagedProblem busy) { return false; } });
            port.reply = confirmed("a"); f.phase(task, Phase.IDENTITY_UNVERIFIED);
            assertThat(port.codes).containsExactly("111111", "222222");
        }
    }
    @Test void refreshedCancelledAndDisabledTasksDiscardLateNetworkResults() throws Exception {
        try (var f = new Fixture(true)) {
            var a = f.users.create("a"); var task = f.create(a); f.ready(task);
            var port = f.ports.getFirst(); var gate = new CountDownLatch(1); port.gate = gate; port.reply = confirmed("a");
            f.coordinator.tick(); await(() -> port.polling.get());
            var replacement = f.coordinator.create(a.id(), Mode.INITIAL, task.authEpoch(), task.id(), false);
            assertThat(f.coordinator.get(a.id(), task.id()).phase()).isEqualTo(Phase.CANCELLED);
            rejected(ManagedProblem.Code.CONFLICT, () -> f.coordinator.create(a.id(), Mode.INITIAL, task.authEpoch(), task.id(), false));
            gate.countDown(); f.ready(replacement);
            f.users.setEnabled(a.id(), false); f.users.setEnabled(a.id(), true);
            f.ports.getLast().reply = confirmed("a"); f.coordinator.tick();
            assertThat(f.coordinator.get(a.id(), replacement.id()).phase()).isEqualTo(Phase.CANCELLED);
            assertThat(f.activated).isEmpty(); rejected(ManagedProblem.Code.UNAUTHORIZED, () -> f.users.scope(a.id()));
        }
    }
    @Test void expirationRemovesImagesAndPairingWithoutWaitingForNetwork() throws Exception {
        try (var f = new Fixture(false)) {
            var a = f.users.create("a"); var task = f.create(a); f.ready(task);
            clock.now = clock.now.plusSeconds(8 * 60);
            assertThat(f.coordinator.get(a.id(), task.id()).phase()).isEqualTo(Phase.EXPIRED);
            assertThat(directory.resolve("binding-materials").resolve(task.id())).doesNotExist();
            rejected(ManagedProblem.Code.EXPIRED, () -> f.coordinator.image(a.id(), task.id()));
            assertThat(f.ports.getFirst().closed).isTrue();
        }
    }
    @Test void uniqueIdentityConflictAndExplicitReplacementRefreshPreserveOwnershipBoundary() throws Exception {
        try (var f = new Fixture(true)) {
            var a = f.users.create("a"); var aa = f.create(a); f.ready(aa); f.ports.getLast().reply = confirmed("a"); f.phase(aa, Phase.SUCCEEDED);
            var old = f.users.scope(a.id()); var b = f.users.create("b"); var bb = f.create(b); f.ready(bb);
            f.ports.getLast().reply = confirmed("a"); f.phase(bb, Phase.CONFLICT);
            var wrong = f.coordinator.create(a.id(), Mode.REAUTHENTICATE, old.authEpoch(), null, false); f.ready(wrong);
            f.ports.getLast().reply = confirmed("someone-else"); f.phase(wrong, Phase.FAILED);
            assertThat(f.users.scope(a.id())).isEqualTo(old);
            rejected(ManagedProblem.Code.INVALID_INPUT, () -> f.coordinator.create(a.id(), Mode.REPLACE, old.authEpoch(), null, false));
            var replace = f.coordinator.create(a.id(), Mode.REPLACE, old.authEpoch(), null, true);
            rejected(ManagedProblem.Code.UNAUTHORIZED, () -> f.users.scope(a.id()));
            var refresh = f.coordinator.create(a.id(), Mode.REPLACE, replace.authEpoch(), replace.id(), true);
            assertThat(refresh.authEpoch()).isEqualTo(replace.authEpoch()); f.ready(refresh);
            f.ports.getLast().reply = confirmed("new"); f.phase(refresh, Phase.SUCCEEDED);
            assertThat(f.users.scope(a.id()).bindingId()).isNotEqualTo(old.bindingId());
        }
    }
    @Test void restartInvalidatesOldInvitationsAndCleansOnlyPrivateKnownFiles() throws Exception {
        String userId; String taskId;
        try (var f = new Fixture(false)) {
            var a = f.users.create("a"); var task = f.create(a); f.ready(task); userId = a.id(); taskId = task.id();
        }
        try (var f = new Fixture(false)) {
            assertThat(f.coordinator.get(userId, taskId).phase()).isEqualTo(Phase.CANCELLED);
            assertThat(directory.resolve("binding-materials").resolve(taskId)).doesNotExist();
        }
        var materials = new BindingMaterials(directory);
        String id = UUID.randomUUID().toString(); materials.create(id); materials.writeQr(id, "synthetic");
        var secret = directory.resolve("binding-materials").resolve(id).resolve("unexpected.txt"); Files.writeString(secret, "keep");
        rejected(ManagedProblem.Code.INVALID_DIRECTORY, () -> new BindingMaterials(directory));
        assertThat(Files.readString(secret)).isEqualTo("keep");
        assertThat(secret.resolveSibling("qr.png")).exists();
        rejected(ManagedProblem.Code.INVALID_INPUT, () -> materials.clear("../outside"));
    }
    @Test void shutdownAndPendingCapacityAreBoundedAndNoStartupNetworkOccurs() {
        try (var f = new Fixture(false, 1)) {
            assertThat(f.ports).isEmpty(); var a = f.users.create("a"); var b = f.users.create("b"); var aa = f.create(a);
            rejected(ManagedProblem.Code.BACKLOG, () -> f.create(b));
            assertThat(f.ports).hasSize(1); f.coordinator.cancel(a.id(), aa.id());
            assertThat(f.create(b)).isNotNull();
        }
    }
    private class Fixture implements AutoCloseable {
        final ManagedStore store = ManagedStore.open(directory);
        final ManagedUsers users = new ManagedUsers(store, clock);
        final List<FakePort> ports = new CopyOnWriteArrayList<>();
        final List<ManagedScope> activated = new CopyOnWriteArrayList<>();
        final BindingCoordinator coordinator;
        Fixture(boolean syntheticIdentity) { this(syntheticIdentity, 16); }
        Fixture(boolean syntheticIdentity, int capacity) {
            ScannerIdentityResolver resolver = syntheticIdentity ? evidence -> {
                var c = evidence.login().credentials(); String id = c.scanUserId().substring("scanner-".length());
                return new ScannerIdentityResolver.VerifiedIdentity("synthetic-global", id, c.botId(), "sender-" + id, c.origin(), "test-only");
            } : ScannerIdentityResolver.production();
            coordinator = new BindingCoordinator(users, new BindingMaterials(directory), resolver,
                    () -> { var port = new FakePort(ports.size()); ports.add(port); return port; }, clock, activated::add, capacity);
        }
        BindingCoordinator.Status create(User u) { return coordinator.create(u.id(), Mode.INITIAL, u.authEpoch(), null, false); }
        void ready(BindingCoordinator.Status s) throws Exception { phase(s, Phase.QR_READY); }
        void phase(BindingCoordinator.Status s, Phase expected) throws Exception {
            await(() -> { coordinator.tick(); return coordinator.get(s.userId(), s.id()).phase() == expected; });
        }
        @Override public void close() { coordinator.close(); store.close(); }
    }
    static final class FakePort implements BindingCoordinator.Port {
        final int index;
        volatile LoginStatus reply = new LoginStatus(LoginPhase.WAIT, null, null);
        volatile boolean closed;
        volatile CountDownLatch gate;
        final AtomicBoolean polling = new AtomicBoolean();
        final List<String> codes = new CopyOnWriteArrayList<>();
        FakePort(int index) { this.index = index; }
        @Override public QrCode request() { return new QrCode("private-qr-" + index, "private-display-" + index); }
        @Override public LoginStatus poll(URI origin, QrCode qr, String code) {
            polling.set(true); if (code != null) codes.add(code);
            var result = reply;
            try { if (gate != null) { while (gate.getCount() != 0) { try { if (!gate.await(3, TimeUnit.SECONDS)) throw new AssertionError("gate timeout"); } catch (InterruptedException ignored) { } } } return result; }
            finally { polling.set(false); }
        }
        @Override public void validate(Credentials credentials) { if (credentials == null) throw new IllegalArgumentException("synthetic missing credentials"); }
        @Override public void close() { closed = true; }
    }
    static LoginStatus confirmed(String id) { return new LoginStatus(LoginPhase.CONFIRMED, null, new Credentials("bot-" + id, "secret-token-" + id, WechatApiClient.LOGIN_ORIGIN, "scanner-" + id)); }
    private static void await(java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean()) { if (System.nanoTime() > deadline) throw new AssertionError("Synthetic task timed out"); Thread.sleep(10); }
    }
    private static void rejected(ManagedProblem.Code code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ManagedProblem.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-09-29T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
