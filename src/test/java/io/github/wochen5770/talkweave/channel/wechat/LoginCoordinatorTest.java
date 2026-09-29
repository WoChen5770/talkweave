package io.github.wochen5770.talkweave.channel.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wochen5770.talkweave.persistence.ConversationRepository;
import io.github.wochen5770.talkweave.persistence.SqliteStore;
import io.github.wochen5770.talkweave.runtime.PrivateStateFiles;
import io.github.wochen5770.talkweave.support.TestProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginPhase.*;
import static org.assertj.core.api.Assertions.*;

class LoginCoordinatorTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final class Port implements LoginCoordinator.Port {
        final ArrayDeque<WechatApiClient.LoginStatus> replies = new ArrayDeque<>();
        int requests; int polls; String submittedCode;
        Runnable onPoll = () -> { };
        public WechatApiClient.QrCode requestQr() { requests++; return new WechatApiClient.QrCode("synthetic-qr-token", "https://example.invalid/synthetic-qr"); }
        public WechatApiClient.LoginStatus poll(URI origin, WechatApiClient.QrCode qr, String code) { polls++; submittedCode = code; onPoll.run(); return replies.remove(); }
        public void validate(WechatApiClient.Credentials credentials) { assertThat(credentials.token()).isEqualTo("synthetic-token"); }
    }
    private WechatApiClient.LoginStatus phase(WechatApiClient.LoginPhase phase) { return new WechatApiClient.LoginStatus(phase, null, null); }
    private WechatApiClient.LoginStatus confirmed() { return new WechatApiClient.LoginStatus(CONFIRMED, null, new WechatApiClient.Credentials("bot", "synthetic-token", URI.create("https://ilinkai.weixin.qq.com"), "scanner")); }
    private ConversationRepository repository(SqliteStore store) { return new ConversationRepository(store, new ConversationRepository.Binding("", ""), TestProperties.valid(temp).storage()); }
    private PrivateStateFiles files() throws Exception { return new PrivateStateFiles(temp.resolve("login"), false); }
    private void submit(String code, String challenge) throws Exception {
        Files.writeString(temp.resolve("login/verify-code.json"), json.writeValueAsString(Map.of("code", code, "challengeId", challenge)));
    }
    private String challenge() throws Exception { return json.readTree(Files.readString(temp.resolve("login/status.json"))).path("challengeId").asText(); }

    @Test void pairingIsBoundAndOneTimeThenCredentialsSurviveRestartWithoutQr() throws Exception {
        Port port = new Port();
        try (var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            var repo = repository(store);
            try (var coordinator = new LoginCoordinator(repo, files(), port, clock, Duration.ofMinutes(2))) {
                assertThat(coordinator.tick()).isFalse();
                assertThat(temp.resolve("login/qr.png")).exists();
                port.replies.add(phase(NEED_VERIFY_CODE)); coordinator.tick();
                submit("123", "expired-challenge"); coordinator.tick(); assertThat(port.polls).isEqualTo(1);
                submit("999", challenge()); port.replies.add(phase(NEED_VERIFY_CODE)); coordinator.tick();
                assertThat(port.submittedCode).isEqualTo("999");
                coordinator.tick(); assertThat(port.polls).isEqualTo(2);
                submit("123", challenge()); port.replies.add(confirmed()); assertThat(coordinator.tick()).isTrue();
                assertThat(repo.session().orElseThrow().token()).isEqualTo("synthetic-token");
                assertThat(temp.resolve("login/qr.png")).doesNotExist();
                assertThat(temp.resolve("login/verify-code.json")).doesNotExist();
                assertThat(Files.readString(temp.resolve("login/status.json"))).doesNotContain("synthetic-token", "synthetic-qr-token", "123");
            }
        }
        try (var store = SqliteStore.open(TestProperties.valid(temp).storage());
             var restored = new LoginCoordinator(repository(store), files(), port, clock, Duration.ofMinutes(2))) {
            assertThat(restored.tick()).isTrue();
            assertThat(port.requests).isEqualTo(1);
        }
    }

    @Test void expirationClearsMaterialsAndRotatesChallengeWithoutSendingStaleCode() throws Exception {
        try (var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            Port port = new Port();
            try (var login = new LoginCoordinator(repository(store), files(), port, clock, Duration.ofSeconds(10))) {
                login.tick(); String first = challenge();
                submit("123", first); clock.advance(11); login.tick();
                assertThat(login.phase()).isEqualTo("EXPIRED");
                assertThat(temp.resolve("login/qr.png")).doesNotExist();
                assertThat(temp.resolve("login/verify-code.json")).doesNotExist();
                login.tick(); assertThat(challenge()).isNotEqualTo(first);
                assertThat(port.polls).isZero();
            }
        }
    }

    @Test void blockedPairingDoesNotTightlyReissueQrAndOversizeInputIsRejected() throws Exception {
        try (var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            Port port = new Port();
            try (var login = new LoginCoordinator(repository(store), files(), port, clock, Duration.ofMinutes(2))) {
                login.tick(); port.replies.add(phase(NEED_VERIFY_CODE)); login.tick();
                Files.writeString(temp.resolve("login/verify-code.json"), "x".repeat(3000)); login.tick();
                assertThat(login.phase()).isEqualTo("PAIRING_INPUT_REJECTED"); assertThat(port.polls).isEqualTo(1);
                submit("123", challenge()); port.replies.add(phase(VERIFY_CODE_BLOCKED)); login.tick();
                assertThat(login.phase()).isEqualTo("VERIFY_CODE_BLOCKED");
                for (int i = 0; i < 5; i++) login.tick();
                assertThat(port.requests).isEqualTo(1);
                assertThat(temp.resolve("login/qr.png")).doesNotExist();
            }
        }
    }

    @Test void lateConfirmedReplyCannotReplaceNewSessionOrExpiredChallenge() throws Exception {
        try (var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            var repo = repository(store); Port port = new Port();
            try (var login = new LoginCoordinator(repo, files(), port, clock, Duration.ofSeconds(10))) {
                login.tick(); port.replies.add(confirmed());
                port.onPoll = () -> repo.installSession("new-bot", "https://ilinkai.weixin.qq.com", "synthetic-token", "new-scanner");
                assertThat(login.tick()).isFalse();
                assertThat(repo.session().orElseThrow().botId()).isEqualTo("new-bot");
                repo.invalidateSession(repo.session().orElseThrow().generation());
                login.tick(); port.onPoll = () -> clock.advance(11); port.replies.add(confirmed());
                assertThat(login.tick()).isFalse(); assertThat(login.phase()).isEqualTo("EXPIRED");
                assertThat(repo.session().orElseThrow().active()).isFalse();
            }
        }
    }

    @Test void candidateRequiresRandomChallengeAndNeverAutoBindsOrStoresIncomingText() throws Exception {
        try (var store = SqliteStore.open(TestProperties.valid(temp).storage())) {
            var repo = repository(store);
            var session = repo.installSession("bot", "https://ilinkai.weixin.qq.com", "synthetic-token", "scanner");
            var verifier = new IdentityVerifier(repo, files()); verifier.prepare(session);
            String expected = json.readTree(Files.readString(temp.resolve("login/identity.json"))).path("expectedMessage").asText();
            var unrelated = new ConversationRepository.Inbound("1", "stranger", "other-private-text", "ctx", false, true, true, true);
            verifier.observe(session, List.of(unrelated));
            assertThat(Files.readString(temp.resolve("login/identity.json"))).doesNotContain("other-private-text", "stranger");
            var correct = new ConversationRepository.Inbound("2", "owner", expected, "ctx", false, true, true, true);
            verifier.observe(session, List.of(correct));
            var candidate = json.readTree(Files.readString(temp.resolve("login/identity.json")));
            assertThat(candidate.path("ownerId").asText()).isEqualTo("owner");
            assertThat(candidate.has("expectedMessage")).isFalse();
            assertThat(repo.isBound(session)).isFalse();
            repo.acceptBatch(session.generation(), "", "cursor", List.of(correct));
            assertThat(repo.pendingCount()).isZero();
            var next = repo.installSession("new-bot", "https://ilinkai.weixin.qq.com", "synthetic-token", "scanner");
            verifier.prepare(next);
            assertThat(Files.readString(temp.resolve("login/identity.json"))).doesNotContain("ownerId");
        }
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}