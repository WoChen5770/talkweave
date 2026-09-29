package io.github.wochen5770.talkweave.channel.wechat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static io.github.wochen5770.talkweave.channel.wechat.ScannerIdentityResolver.*;

class ScannerIdentityResolverTest {
    private final ScannerIdentityResolver resolver = ScannerIdentityResolver.production();
    private final URI origin = URI.create("https://ilinkai.weixin.qq.com");

    @Test void scanningIsNotConfirmedLoginEvenWhenCredentialsArePresent() {
        var result = resolve(WechatApiClient.LoginPhase.SCANNED, "fake-scanner");
        assertThat(result).isEqualTo(new Unverified(Reason.LOGIN_NOT_CONFIRMED));
    }

    @Test void missingScannerIsNotTheFirstMessageSender() {
        assertThat(resolve(WechatApiClient.LoginPhase.CONFIRMED, ""))
                .isEqualTo(new Unverified(Reason.MISSING_LOGIN_IDENTITY));
        assertThat(resolve(WechatApiClient.LoginPhase.CONFIRMED, null))
                .isEqualTo(new Unverified(Reason.MISSING_LOGIN_IDENTITY));
    }

    @Test void coincidentallyEqualOrBotScopedIdsCannotAuthorizeAnOwner() {
        for (String scanner : new String[]{"fake-same-as-message-sender", "fake-bot-local-user", "fake-other-first-sender"}) {
            assertThat(resolve(WechatApiClient.LoginPhase.CONFIRMED, scanner))
                    .isEqualTo(new Unverified(Reason.PROTOCOL_NOT_VERIFIED));
        }
    }

    @Test void revisionAndSystemPropertiesCannotEnableAnUnverifiedProtocol() {
        var login = new WechatApiClient.LoginStatus(WechatApiClient.LoginPhase.CONFIRMED, null,
                new WechatApiClient.Credentials("fake-bot", "fake-token", origin, "fake-scanner"));
        for (String revision : new String[]{"2.4.8", "2.4.10", "trusted", "verified", "synthetic"}) {
            assertThat(resolver.resolve(new LoginEvidence(login, revision)))
                    .isEqualTo(new Unverified(Reason.PROTOCOL_NOT_VERIFIED));
        }
    }

    @Test void pinnedProtocolUsesConfirmedScannerWithoutWaitingForAnyMessage() {
        for (String bot : new String[]{"fake-original-bot", "fake-renewed-bot"}) {
            var login = new WechatApiClient.LoginStatus(WechatApiClient.LoginPhase.CONFIRMED, null,
                    new WechatApiClient.Credentials(bot, "fake-token", origin, "fake-account"));
            assertThat(resolver.resolve(new LoginEvidence(login, VERIFIED_PROTOCOL)))
                    .isEqualTo(new VerifiedIdentity(ACCOUNT_NAMESPACE, "fake-account", bot, "fake-account", origin, EVIDENCE_REVISION));
        }
    }

    @Test void pinnedRevisionDoesNotAuthorizeUnknownOriginsOrMalformedIdentities() {
        for (String url : new String[]{"https://other.invalid", "http://ilinkai.weixin.qq.com",
                "https://ilinkai.weixin.qq.com/other", "https://ilinkai.weixin.qq.com?x=1"}) {
            var login = new WechatApiClient.LoginStatus(WechatApiClient.LoginPhase.CONFIRMED, null,
                    new WechatApiClient.Credentials("fake-bot", "fake-token", URI.create(url), "fake-account"));
            assertThat(resolver.resolve(new LoginEvidence(login, VERIFIED_PROTOCOL)))
                    .isEqualTo(new Unverified(Reason.PROTOCOL_NOT_VERIFIED));
        }
        for (String scanner : new String[]{"fake-bot", " fake-account", "fake\naccount", "a".repeat(513)}) {
            var login = new WechatApiClient.LoginStatus(WechatApiClient.LoginPhase.CONFIRMED, null,
                    new WechatApiClient.Credentials("fake-bot", "fake-token", origin, scanner));
            assertThat(resolver.resolve(new LoginEvidence(login, VERIFIED_PROTOCOL)))
                    .isEqualTo(new Unverified(Reason.MISSING_LOGIN_IDENTITY));
        }
    }

    @Test void trustedOutputRequiresCompleteNamespaceAndDoesNotLeakIdentity() {
        assertThatThrownBy(() -> new VerifiedIdentity("", "fake-account", "fake-bot", "fake-sender", origin, "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifiedIdentity("scope", "fake-account", "fake-bot", "fake-sender",
                URI.create("http://evil.invalid"), "test")).isInstanceOf(IllegalArgumentException.class);
        var identity = new VerifiedIdentity("test-only-global", "fake-account", "fake-bot", "fake-sender", origin, "test-only");
        assertThat(identity.toString()).doesNotContain("fake-account", "fake-bot", "fake-sender");
    }

    private Resolution resolve(WechatApiClient.LoginPhase phase, String scanner) {
        return resolver.resolve(new LoginEvidence(new WechatApiClient.LoginStatus(phase, null,
                new WechatApiClient.Credentials("fake-bot", "fake-token", origin, scanner)), "reference-2.4.9"));
    }
}
