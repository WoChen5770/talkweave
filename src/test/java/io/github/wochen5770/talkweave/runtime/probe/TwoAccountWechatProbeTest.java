package io.github.wochen5770.talkweave.runtime.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class TwoAccountWechatProbeTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void observesTwoConnectionsButDoesNotApproveIdentityOrPrintSecrets(CapturedOutput output) throws Exception {
        Path root = directory.resolve("probe"); var ports = new FakePorts(root, false);
        var probe = new TwoAccountWechatProbe(root, ports, Clock.systemUTC(), Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertThat(probe.run(false)).isTrue();
        var evidence = json.readTree(root.resolve("evidence.json").toFile());
        assertThat(evidence.path("automaticBindingApproved").asBoolean()).isFalse();
        assertThat(evidence.at("/coexistence/coexist-a/sendConfirmed").asBoolean()).isTrue();
        assertThat(evidence.at("/coexistence/coexist-b/sendConfirmed").asBoolean()).isTrue();
        assertThat(evidence.path("relogin").asText()).isEqualTo("NOT_REQUESTED_NOT_VERIFIED");
        assertThat(root.resolve("a-login/qr.png")).doesNotExist();
        assertThat(root.resolve("b-login/qr.png")).doesNotExist();
        assertThat(ports.closed.get()).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain("fake-token", "fake-scanner", "fake-sender", "wx-multi-");
    }

    @Test void explicitlyRequestedReloginRecordsOldTokenRejectionWithoutStoppingOtherAccount() throws Exception {
        Path root = directory.resolve("probe"); var ports = new FakePorts(root, false);
        assertThat(new TwoAccountWechatProbe(root, ports, Clock.systemUTC(), Duration.ofSeconds(2), Duration.ofSeconds(2)).run(true)).isTrue();
        var evidence = json.readTree(root.resolve("evidence.json").toFile());
        assertThat(evidence.path("reloginBotIdUnchanged").asBoolean()).isTrue();
        assertThat(evidence.at("/afterRelogin/after-old-a/result").asText()).isEqualTo("REMOTE_HTTP_-14");
        assertThat(evidence.at("/afterRelogin/after-b/sendConfirmed").asBoolean()).isTrue();
        assertThat(evidence.at("/afterRelogin/after-new-a/sendConfirmed").asBoolean()).isTrue();
        assertThat(evidence.path("automaticBindingApproved").asBoolean()).isFalse();
    }

    @Test void unknownSendIsNotRetriedAndExistingDirectoryCannotBeReused() throws Exception {
        Path root = directory.resolve("probe"); var ports = new FakePorts(root, true);
        assertThat(new TwoAccountWechatProbe(root, ports, Clock.systemUTC(), Duration.ofSeconds(2), Duration.ofSeconds(2)).run(false)).isTrue();
        var evidence = json.readTree(root.resolve("evidence.json").toFile());
        assertThat(evidence.at("/coexistence/coexist-a/result").asText()).isEqualTo("DELIVERY_UNKNOWN");
        assertThat(ports.sends.get()).isEqualTo(2);
        assertThatThrownBy(() -> new TwoAccountWechatProbe(root, ports, Clock.systemUTC(), Duration.ofSeconds(2), Duration.ofSeconds(2)))
                .isInstanceOf(FileAlreadyExistsException.class);
    }

    private class FakePorts implements TwoAccountWechatProbe.Ports {
        final Path root; final boolean failSend;
        final AtomicInteger created = new AtomicInteger(); final AtomicInteger closed = new AtomicInteger(); final AtomicInteger sends = new AtomicInteger();
        FakePorts(Path root, boolean failSend) { this.root = root; this.failSend = failSend; }
        public TwoAccountWechatProbe.Port create() {
            int index = created.getAndIncrement(); String account = index == 1 ? "b" : "a";
            return new TwoAccountWechatProbe.Port() {
                public QrCode qr() { return new QrCode("fake-qr", "fake-qr-content"); }
                public LoginStatus poll(URI origin, QrCode qr, String code) {
                    return new LoginStatus(LoginPhase.CONFIRMED, null, new Credentials("fake-bot-" + account, "fake-token-" + index,
                            URI.create("https://ilinkai.weixin.qq.com"), "fake-scanner-" + account));
                }
                public Updates updates(Credentials credentials, String cursor) {
                    if (created.get() == 3 && index == 0) throw new RemoteFailure(RemoteFailure.Source.WECHAT, RemoteFailure.Kind.HTTP, -14);
                    String slot = created.get() == 3 ? index == 1 ? "after-b" : "after-new-a" : index == 0 ? "coexist-a" : "coexist-b";
                    try {
                        String expected = json.readTree(root.resolve(slot).resolve("status.json").toFile()).path("expectedMessage").asText();
                        return new Updates(List.of(new Incoming("fake-message-" + slot, "fake-sender-" + account, "fake-context", expected, false, 1, 2)), "fake-cursor", null);
                    } catch (Exception failure) { throw new AssertionError(failure); }
                }
                public void send(Credentials credentials, Incoming message) {
                    sends.incrementAndGet();
                    if (failSend && index == 0) throw new RemoteFailure(RemoteFailure.Source.WECHAT, RemoteFailure.Kind.TIMEOUT);
                }
                public void close() { closed.incrementAndGet(); }
            };
        }
    }
}