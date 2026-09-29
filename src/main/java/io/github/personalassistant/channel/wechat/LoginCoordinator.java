package io.github.personalassistant.channel.wechat;

import io.github.personalassistant.persistence.ConversationRepository;
import io.github.personalassistant.runtime.PrivateStateFiles;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Serialized, headless login. Network retry scheduling is external; stale challenges never replace newer credentials. */
public final class LoginCoordinator implements AutoCloseable {
    public interface Port {
        WechatApiClient.QrCode requestQr();
        WechatApiClient.LoginStatus poll(URI origin, WechatApiClient.QrCode qr, String code);
        void validate(WechatApiClient.Credentials credentials);
    }
    private final ConversationRepository repository;
    private final PrivateStateFiles files;
    private final Port port;
    private final Clock clock;
    private final Duration lifetime;
    private WechatApiClient.QrCode qr;
    private String challenge = "";
    private Instant expires = Instant.EPOCH;
    private URI origin = WechatApiClient.LOGIN_ORIGIN;
    private long baselineGeneration;
    private volatile String phase = "WAITING_LOGIN";
    private boolean pairing;
    private boolean blocked;
    private volatile boolean closed;

    public LoginCoordinator(ConversationRepository repository, PrivateStateFiles files, Port port, Clock clock, Duration lifetime) throws IOException {
        this.repository = repository; this.files = files; this.port = port; this.clock = clock; this.lifetime = lifetime;
        files.clearLoginMaterials(); // Challenges cannot survive restart; credentials can.
    }
    public static Port port(WechatApiClient client) {
        return new Port() {
            public WechatApiClient.QrCode requestQr() { return client.requestQr(List.of()); }
            public WechatApiClient.LoginStatus poll(URI origin, WechatApiClient.QrCode qr, String code) { return client.pollQr(origin, qr, code); }
            public void validate(WechatApiClient.Credentials credentials) { client.validateCredentials(credentials); }
        };
    }
    public String phase() { return phase; }
    public void cancel() { closed = true; }

    /** One tick. No terminal input and no implicit trusting of the first incoming sender. */
    public synchronized boolean tick() throws Exception {
        if (closed) return false;
        var session = repository.session();
        if (session.isPresent() && session.get().active()) {
            port.validate(InboxReceiver.credentials(session.get()));
            if (!phase.equals("CONNECTED")) { clear(); status("CONNECTED"); }
            return true;
        }
        if (blocked) return false;
        if (qr == null) {
            baselineGeneration = session.map(ConversationRepository.Session::generation).orElse(0L);
            challenge = UUID.randomUUID().toString();
            expires = clock.instant().plus(lifetime);
            origin = WechatApiClient.LOGIN_ORIGIN;
            pairing = false;
            status("REQUESTING_QR");
            var requested = port.requestQr();
            if (closed || !clock.instant().isBefore(expires)) { clear(); status("EXPIRED"); return false; }
            files.writeQr(requested.displayContent());
            qr = requested;
            status("QR_READY");
            return false;
        }
        if (closed || !clock.instant().isBefore(expires)) { clear(); status("EXPIRED"); return false; }
        String code = null;
        if (pairing) {
            try {
                files.prepareVerification(challenge);
                code = files.consumeVerification(challenge);
            } catch (IOException rejected) { status("PAIRING_INPUT_REJECTED"); return false; }
            if (code == null) return false;
        }
        var response = port.poll(origin, qr, code);
        if (closed || !clock.instant().isBefore(expires)) { clear(); status("EXPIRED"); return false; }
        switch (response.phase()) {
            case WAIT -> { pairing = false; status("QR_READY"); }
            case SCANNED -> { pairing = false; status("SCANNED"); }
            case NEED_VERIFY_CODE -> { pairing = true; files.prepareVerification(challenge); status("NEED_PAIRING"); }
            case REDIRECT -> { origin = response.redirect(); status("REDIRECTING"); }
            case EXPIRED -> { clear(); status("EXPIRED"); }
            case VERIFY_CODE_BLOCKED, BOUND_REDIRECT -> { clear(); blocked = true; status(response.phase().name()); }
            case CONFIRMED -> {
                var credentials = response.credentials();
                port.validate(credentials);
                var installed = repository.installSessionIfGeneration(baselineGeneration, credentials.botId(), credentials.origin().toString(), credentials.token(), credentials.scanUserId());
                clear();
                status(installed.isPresent() ? "CONNECTED" : "STALE_CHALLENGE");
                return installed.isPresent();
            }
        }
        return false;
    }
    private void status(String next) throws IOException {
        if (phase.equals(next) && !next.equals("REQUESTING_QR")) return;
        files.writeJson("status.json", Map.of("phase", next, "challengeId", challenge,
                "expiresAt", expires.toString(), "updatedAt", clock.instant().toString()));
        phase = next;
    }
    private void clear() throws IOException { files.clearLoginMaterials(); qr = null; pairing = false; }
    @Override public synchronized void close() throws IOException { closed = true; clear(); status("STOPPED"); }
}