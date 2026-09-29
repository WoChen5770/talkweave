package io.github.personalassistant.runtime.probe;

import io.github.personalassistant.channel.wechat.WechatApiClient;
import io.github.personalassistant.runtime.RemoteFailure;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit opt-in manual connectivity check; never starts Spring or invokes a model. */
public final class WechatConnectivityProbe {
    public static final String REPLY = "微信联调成功：Java 服务已收到测试消息并回复。";
    private final PrivateProbeFiles files;
    private final String challengeId = UUID.randomUUID().toString();
    private final String expectedMessage;
    private String phase = "STARTING";
    private Instant deadline = Instant.now().plusSeconds(480);

    private WechatConnectivityProbe(PrivateProbeFiles files) {
        this.files = files;
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        expectedMessage = "wx-check-" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
    }

    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("--allow-live-wechat")) {
            System.err.println("Explicit opt-in required: --allow-live-wechat <new-private-directory>");
            System.exit(2);
        }
        WechatConnectivityProbe probe = null;
        int result = 1;
        try {
            probe = new WechatConnectivityProbe(new PrivateProbeFiles(Path.of(args[1])));
            probe.run();
            result = probe.phase.equals("COMPLETED") ? 0 : 1;
        } catch (Exception failure) {
            if (probe != null) {
                String safe = failure instanceof RemoteFailure remote ? remote.kind().name() : "LOCAL_OPERATION";
                try { probe.status(probe.phase.equals("SENDING") ? "DELIVERY_UNKNOWN" : "FAILED_" + safe); }
                catch (Exception ignored) { }
            }
            System.err.println("Probe stopped; consult the protected status file. No automatic retry was made.");
        } finally {
            if (probe != null) try { probe.files.clearLoginMaterials(); } catch (Exception ignored) { }
        }
        System.exit(result);
    }

    private void run() throws Exception {
        try (var client = new WechatApiClient(Duration.ofSeconds(15), Duration.ofSeconds(45))) {
            status("REQUESTING_QR");
            var qr = client.requestQr(List.of());
            files.writeQr(qr.displayContent());
            status("QR_READY");
            URI origin = WechatApiClient.LOGIN_ORIGIN;
            WechatApiClient.Credentials credentials = null;
            String verificationCode = null;
            while (Instant.now().isBefore(deadline) && credentials == null) {
                if (phase.equals("NEED_PAIRING")) {
                    files.prepareVerification(challengeId);
                    verificationCode = files.consumeVerification(challengeId);
                    if (verificationCode == null) { Thread.sleep(500); continue; }
                }
                WechatApiClient.LoginStatus login;
                try { login = client.pollQr(origin, qr, verificationCode); }
                catch (RemoteFailure failure) {
                    if (failure.kind() == RemoteFailure.Kind.TIMEOUT) { Thread.sleep(1000); continue; }
                    throw failure;
                }
                verificationCode = null;
                switch (login.phase()) {
                    case WAIT -> status("QR_READY");
                    case SCANNED -> status("SCANNED");
                    case NEED_VERIFY_CODE -> { files.prepareVerification(challengeId); status("NEED_PAIRING"); }
                    case REDIRECT -> origin = login.redirect();
                    case CONFIRMED -> credentials = login.credentials();
                    case EXPIRED, VERIFY_CODE_BLOCKED, BOUND_REDIRECT -> { status(login.phase().name()); return; }
                }
                if (credentials == null) Thread.sleep(1000);
            }
            if (credentials == null) { status("EXPIRED"); return; }
            files.clearLoginMaterials();
            files.writeJson("credentials.json", Map.of("botId", credentials.botId(), "token", credentials.token(),
                    "origin", credentials.origin().toString(), "scanUserId", credentials.scanUserId()));
            deadline = Instant.now().plusSeconds(300);
            status("WAITING_TEST_MESSAGE");
            String cursor = "";
            while (Instant.now().isBefore(deadline)) {
                WechatApiClient.Updates updates;
                try { updates = client.getUpdates(credentials, cursor); }
                catch (RemoteFailure failure) {
                    if (failure.kind() == RemoteFailure.Kind.TIMEOUT) continue;
                    throw failure;
                }
                cursor = updates.cursor();
                for (var message : updates.messages()) {
                    // The nonce is provided out-of-band to the operator, not derived from the first sender.
                    if (!matches(expectedMessage, message)) continue;
                    files.writeJson("evidence.json", Map.of(
                            "botId", credentials.botId(), "scanUserId", credentials.scanUserId(),
                            "verifiedSenderId", message.sender(), "messageId", message.messageId(),
                            "contextTokenPresent", true, "nonceMatched", true,
                            "replyUtf8Bytes", REPLY.getBytes(StandardCharsets.UTF_8).length,
                            "origin", credentials.origin().toString(), "sendConfirmed", false));
                    status("SENDING"); // Durable marker before the sole external send; never resume or replay this run.
                    client.sendText(credentials, message.sender(), message.contextToken(), "probe-" + UUID.randomUUID(), REPLY);
                    files.writeJson("evidence.json", Map.of(
                            "botId", credentials.botId(), "scanUserId", credentials.scanUserId(),
                            "verifiedSenderId", message.sender(), "messageId", message.messageId(),
                            "contextTokenPresent", true, "nonceMatched", true,
                            "replyUtf8Bytes", REPLY.getBytes(StandardCharsets.UTF_8).length,
                            "origin", credentials.origin().toString(), "sendConfirmed", true));
                    status("COMPLETED");
                    return;
                }
                Thread.sleep(500);
            }
            status("TEST_MESSAGE_TIMEOUT");
        }
    }

    public static boolean matches(String expected, WechatApiClient.Incoming message) {
        return !message.group() && message.messageType() == 1 && message.messageState() == 2
                && message.messageId() != null && !message.messageId().isBlank()
                && message.sender() != null && !message.sender().isBlank()
                && message.contextToken() != null && !message.contextToken().isBlank()
                && message.text() != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                message.text().getBytes(StandardCharsets.UTF_8));
    }

    private void status(String next) throws Exception {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("phase", next);
        safe.put("challengeId", challengeId);
        safe.put("expectedMessage", expectedMessage);
        safe.put("expiresAt", deadline.toString());
        safe.put("updatedAt", Instant.now().toString());
        files.writeJson("status.json", safe);
        if (!phase.equals(next)) System.out.println("Probe phase: " + next);
        phase = next;
    }
}
