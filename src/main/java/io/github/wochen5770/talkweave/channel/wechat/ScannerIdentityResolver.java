package io.github.wochen5770.talkweave.channel.wechat;

import java.net.URI;
import java.util.Objects;

/**
 * Trust boundary between a confirmed login and an identity usable for authorization.
 * It deliberately accepts no inbound message: receiving a first message is not proof
 * that its sender scanned the invitation. Implementations must be backed by protocol
 * evidence, never by an administrator-supplied owner ID or an equality heuristic.
 */
public interface ScannerIdentityResolver {
    Resolution resolve(LoginEvidence evidence);

    record LoginEvidence(WechatApiClient.LoginStatus login, String protocolRevision) {
        public LoginEvidence {
            Objects.requireNonNull(login, "login");
            if (protocolRevision == null || protocolRevision.isBlank()) {
                throw new IllegalArgumentException("Protocol revision is required");
            }
        }
        @Override public String toString() { return "LoginEvidence[REDACTED]"; }
    }

    sealed interface Resolution permits VerifiedIdentity, Unverified { }

    /** namespace/accountId MUST identify the account beyond a single bot conversation. */
    record VerifiedIdentity(String namespace, String accountId, String botId, String senderId,
                            URI origin, String evidenceRevision) implements Resolution {
        public VerifiedIdentity {
            for (String value : new String[]{namespace, accountId, botId, senderId, evidenceRevision}) {
                if (value == null || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Invalid verified identity");
                }
            }
            if (origin == null || !"https".equals(origin.getScheme()) || origin.getHost() == null
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || (origin.getPort() != -1 && origin.getPort() != 443)
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty() && !"/".equals(origin.getRawPath()))) {
                throw new IllegalArgumentException("Invalid verified origin");
            }
        }
        @Override public String toString() { return "VerifiedIdentity[REDACTED]"; }
    }

    enum Reason { LOGIN_NOT_CONFIRMED, MISSING_LOGIN_IDENTITY, PROTOCOL_NOT_VERIFIED }
    record Unverified(Reason reason) implements Resolution {
        public Unverified { Objects.requireNonNull(reason); }
    }

    String VERIFIED_PROTOCOL = "2.4.9";
    String ACCOUNT_NAMESPACE = "weixin-ilink:ilinkai.weixin.qq.com:bot-type-3";
    String EVIDENCE_REVISION = "tencent-24de5c9-20260929-two-account-relogin";

    /** Pinned upstream login -> saved userId -> inbound allowFrom contract; see docs/multi-user-protocol.md.
     * No inbound message, administrator override or synthetic revision can establish identity.
     */
    static ScannerIdentityResolver production() {
        return evidence -> {
            var login = evidence.login();
            if (login.phase() != WechatApiClient.LoginPhase.CONFIRMED) {
                return new Unverified(Reason.LOGIN_NOT_CONFIRMED);
            }
            var credentials = login.credentials();
            if (credentials == null || blank(credentials.botId()) || blank(credentials.token())
                    || credentials.origin() == null || blank(credentials.scanUserId())) {
                return new Unverified(Reason.MISSING_LOGIN_IDENTITY);
            }
            if (!VERIFIED_PROTOCOL.equals(evidence.protocolRevision())
                    || !(URI.create("https://ilinkai.weixin.qq.com").equals(credentials.origin())
                    || URI.create("https://ilinkai.weixin.qq.com/").equals(credentials.origin()))) {
                return new Unverified(Reason.PROTOCOL_NOT_VERIFIED);
            }
            if (!validId(credentials.scanUserId()) || !validId(credentials.botId())
                    || credentials.scanUserId().equals(credentials.botId())
                    || credentials.token().chars().anyMatch(Character::isISOControl)) {
                return new Unverified(Reason.MISSING_LOGIN_IDENTITY);
            }
            return new VerifiedIdentity(ACCOUNT_NAMESPACE, credentials.scanUserId(), credentials.botId(),
                    credentials.scanUserId(), credentials.origin(), EVIDENCE_REVISION);
        };
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean validId(String value) {
        return !blank(value) && value.length() <= 512 && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }
}
