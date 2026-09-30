package io.github.wochen5770.talkweave.runtime.probe;

import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.*;

/** Operator opt-in only. Observes protocol behavior; never authorizes a managed user or calls a model. */
public final class TwoAccountWechatProbe {
    interface Port extends AutoCloseable {
        QrCode qr();
        LoginStatus poll(URI origin, QrCode qr, String code);
        Updates updates(Credentials credentials, String cursor);
        void send(Credentials credentials, Incoming message);
        @Override void close();
    }
    interface Ports { Port create(); }
    private record Observation(boolean received, boolean sent, Boolean scannerEqualsSender, String result) {
        Map<String, Object> summary() {
            var values = new LinkedHashMap<String, Object>();
            values.put("received", received); values.put("sendConfirmed", sent);
            values.put("scannerEqualsSender", scannerEqualsSender); values.put("result", result);
            return values;
        }
    }
    private final Path root;
    private final PrivateProbeFiles report;
    private final Ports ports;
    private final Clock clock;
    private final Duration loginBudget;
    private final Duration messageBudget;

    TwoAccountWechatProbe(Path root, Ports ports, Clock clock, Duration loginBudget, Duration messageBudget) throws Exception {
        this.root = root; this.report = new PrivateProbeFiles(root); this.ports = ports;
        this.clock = clock; this.loginBudget = loginBudget; this.messageBudget = messageBudget;
    }
    public static void main(String[] args) {
        boolean relogin = args.length == 3 && args[1].equals("--allow-relogin");
        if ((args.length != 2 && !relogin) || !args[0].equals("--allow-live-wechat")) {
            System.err.println("Use --allow-live-wechat [--allow-relogin] <new-private-directory>; relogin may revoke existing tokens.");
            System.exit(2); return;
        }
        int exit = 1;
        try {
            var probe = new TwoAccountWechatProbe(Path.of(args[args.length - 1]), TwoAccountWechatProbe::livePort,
                    Clock.systemUTC(), Duration.ofMinutes(8), Duration.ofMinutes(5));
            exit = probe.run(relogin) ? 0 : 1;
        } catch (Exception ignored) {
            System.err.println("Probe stopped; inspect protected status files. No credentials or raw errors are printed.");
        }
        System.exit(exit);
    }

    boolean run(boolean relogin) throws Exception {
        report.writeJson("status.json", Map.of("phase", "WAITING_FOR_TWO_LOGINS", "automaticBindingApproved", false));
        try (var a = ports.create(); var b = ports.create()) {
            Credentials ac = login(a, "a-login");
            Credentials bc = login(b, "b-login");
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("automaticBindingApproved", false);
            evidence.put("distinctBotIds", !ac.botId().equals(bc.botId()));
            evidence.put("distinctScannerIds", !Objects.equals(ac.scanUserId(), bc.scanUserId()));
            evidence.put("coexistence", observeTogether(List.of(a, b), List.of(ac, bc), List.of("coexist-a", "coexist-b")));
            report.writeJson("evidence.json", evidence);
            if (relogin) {
                report.writeJson("status.json", Map.of("phase", "SCAN_A_AGAIN_WITH_THE_SAME_ACCOUNT", "automaticBindingApproved", false));
                try (var renewed = ports.create()) {
                    Credentials newer = login(renewed, "a-relogin");
                    evidence.put("reloginBotIdUnchanged", ac.botId().equals(newer.botId()));
                    evidence.put("reloginScannerIdUnchanged", Objects.equals(ac.scanUserId(), newer.scanUserId()));
                    evidence.put("afterRelogin", observeTogether(List.of(a, b, renewed), List.of(ac, bc, newer),
                            List.of("after-old-a", "after-b", "after-new-a")));
                }
            } else evidence.put("relogin", "NOT_REQUESTED_NOT_VERIFIED");
            evidence.put("phase", "OBSERVATIONS_RECORDED_NOT_AN_IDENTITY_GUARANTEE");
            report.writeJson("evidence.json", evidence);
            report.writeJson("status.json", Map.of("phase", "OBSERVATIONS_RECORDED", "automaticBindingApproved", false));
            System.out.println("Two-account observations recorded. Protocol semantics still require review; automatic binding remains unapproved.");
            return true;
        }
    }

    private Credentials login(Port port, String slot) throws Exception {
        var files = new PrivateProbeFiles(root.resolve(slot));
        String challenge = UUID.randomUUID().toString();
        Instant deadline = clock.instant().plus(loginBudget);
        try {
            var qr = port.qr(); files.writeQr(qr.displayContent());
            phase(files, "QR_READY", deadline, challenge, null);
            URI origin = WechatApiClient.LOGIN_ORIGIN;
            boolean pairing = false;
            while (clock.instant().isBefore(deadline)) {
                String code = null;
                if (pairing) {
                    files.prepareVerification(challenge); code = files.consumeVerification(challenge);
                    if (code == null) { Thread.sleep(250); continue; }
                }
                LoginStatus status;
                try { status = port.poll(origin, qr, code); }
                catch (RemoteFailure failure) { if (failure.kind() == RemoteFailure.Kind.TIMEOUT) continue; throw failure; }
                switch (status.phase()) {
                    case WAIT -> { pairing = false; phase(files, "QR_READY", deadline, challenge, null); }
                    case SCANNED -> { pairing = false; phase(files, "SCANNED", deadline, challenge, null); }
                    case NEED_VERIFY_CODE -> { pairing = true; phase(files, "NEED_PAIRING", deadline, challenge, null); }
                    case REDIRECT -> origin = status.redirect();
                    case CONFIRMED -> {
                        Credentials credentials = status.credentials();
                        files.writeJson("credentials.json", Map.of("botId", credentials.botId(), "token", credentials.token(),
                                "origin", credentials.origin().toString(), "scanUserId", Objects.toString(credentials.scanUserId(), "")));
                        phase(files, "LOGIN_CONFIRMED_NOT_AUTHORIZED", deadline, challenge, null);
                        return credentials;
                    }
                    default -> throw new IllegalStateException("Login requires operator intervention");
                }
            }
            phase(files, "EXPIRED", deadline, challenge, null);
            throw new IllegalStateException("Login expired");
        } catch (Exception failure) {
            phase(files, "LOGIN_FAILED", deadline, challenge, null); throw failure;
        } finally { files.clearLoginMaterials(); }
    }

    private Map<String, Object> observeTogether(List<Port> clients, List<Credentials> credentials, List<String> slots) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new ArrayList<Callable<Observation>>();
            for (int i = 0; i < clients.size(); i++) {
                int index = i;
                jobs.add(() -> observe(clients.get(index), credentials.get(index), slots.get(index)));
            }
            var futures = executor.invokeAll(jobs, messageBudget.plusSeconds(60).toMillis(), TimeUnit.MILLISECONDS);
            for (int i = 0; i < futures.size(); i++) {
                var future = futures.get(i);
                result.put(slots.get(i), future.isCancelled() ? Map.of("result", "DEADLINE_EXCEEDED") : future.get().summary());
            }
        }
        return result;
    }

    private Observation observe(Port port, Credentials credentials, String slot) throws Exception {
        var files = new PrivateProbeFiles(root.resolve(slot));
        String expected = "wx-multi-" + UUID.randomUUID();
        Instant deadline = clock.instant().plus(messageBudget);
        boolean sending = false;
        phase(files, "SEND_PRIVATE_TEST_MESSAGE", deadline, "", expected);
        String cursor = "";
        try {
            while (!Thread.currentThread().isInterrupted() && clock.instant().isBefore(deadline)) {
                Updates updates;
                try { updates = port.updates(credentials, cursor); }
                catch (RemoteFailure failure) { if (failure.kind() == RemoteFailure.Kind.TIMEOUT) continue; throw failure; }
                cursor = updates.cursor();
                for (Incoming message : updates.messages()) {
                    if (!ProbeMessages.matches(expected, message)) continue;
                    boolean equal = Objects.equals(credentials.scanUserId(), message.sender());
                    var evidence = new LinkedHashMap<String, Object>();
                    evidence.put("botId", credentials.botId()); evidence.put("scanUserId", credentials.scanUserId());
                    evidence.put("observedSenderId", message.sender()); evidence.put("scannerEqualsSender", equal);
                    evidence.put("sendConfirmed", false); evidence.put("automaticBindingApproved", false);
                    files.writeJson("evidence.json", evidence);
                    phase(files, "SENDING", deadline, "", null); sending = true;
                    port.send(credentials, message);
                    evidence.put("sendConfirmed", true); files.writeJson("evidence.json", evidence);
                    phase(files, "ROUND_TRIP_CONFIRMED", deadline, "", null);
                    return new Observation(true, true, equal, "ROUND_TRIP_CONFIRMED");
                }
            }
            phase(files, "TEST_MESSAGE_TIMEOUT", deadline, "", null);
            return new Observation(false, false, null, "TEST_MESSAGE_TIMEOUT");
        } catch (Exception failure) {
            String result = sending ? "DELIVERY_UNKNOWN" : failure instanceof RemoteFailure remote
                    ? "REMOTE_" + remote.kind().name() + (remote.code() == null ? "" : "_" + remote.code()) : "LOCAL_FAILURE";
            phase(files, result, deadline, "", null);
            return new Observation(sending, false, null, result);
        }
    }

    private static void phase(PrivateProbeFiles files, String phase, Instant deadline, String challenge, String expected) throws Exception {
        var state = new LinkedHashMap<String, Object>();
        state.put("phase", phase); state.put("expiresAt", deadline.toString()); state.put("challengeId", challenge);
        if (expected != null) state.put("expectedMessage", expected);
        files.writeJson("status.json", state);
    }
    private static Port livePort() {
        var client = new WechatApiClient(Duration.ofSeconds(15), Duration.ofSeconds(45));
        return new Port() {
            public QrCode qr() { return client.requestQr(List.of()); }
            public LoginStatus poll(URI origin, QrCode qr, String code) { return client.pollQr(origin, qr, code); }
            public Updates updates(Credentials credentials, String cursor) { return client.getUpdates(credentials, cursor); }
            public void send(Credentials credentials, Incoming message) {
                client.sendText(credentials, message.sender(), message.contextToken(), "probe-" + UUID.randomUUID(), "多账号联调：本账号测试消息已收到。此测试不自动授权用户。");
            }
            public void close() { client.close(); }
        };
    }
}
