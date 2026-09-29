package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.channel.wechat.ScannerIdentityResolver.VerifiedIdentity;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.Credentials;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;
import static io.github.wochen5770.talkweave.managed.persistence.Sql.*;

public final class ManagedUsers {
    public enum Mode { INITIAL, REAUTHENTICATE, REPLACE }
    public enum Phase { REQUESTING_QR, QR_READY, SCANNED, NEED_PAIRING, VERIFYING_IDENTITY,
        SUCCEEDED, EXPIRED, CANCELLED, CONFLICT, IDENTITY_UNVERIFIED, FAILED }
    private static final Set<Phase> OPEN_PHASES = Set.of(Phase.REQUESTING_QR, Phase.QR_READY, Phase.SCANNED, Phase.NEED_PAIRING, Phase.VERIFYING_IDENTITY);
    private static final String PENDING = "('REQUESTING_QR','QR_READY','SCANNED','NEED_PAIRING','VERIFYING_IDENTITY')";
    public record User(String id, String label, boolean enabled, long authEpoch, long createdAt) { }
    public record Attempt(String id, String userId, long authEpoch, Mode mode, Phase phase, long expiresAt, String completedBindingId) { }
    public record ConnectionState(ManagedScope scope, Credentials credentials, String cursor) {
        @Override public String toString() { return "ConnectionState[REDACTED]"; }
    }
    private final ManagedStore store;
    private final Clock clock;
    public ManagedUsers(ManagedStore store, Clock clock) { this.store = store; this.clock = clock; }

    public User create(String label) {
        if (label == null || label.isBlank() || label.length() > 100 || label.chars().anyMatch(Character::isISOControl)) throw new ManagedProblem(INVALID_INPUT);
        return store.transaction(c -> {
            String id = UUID.randomUUID().toString(); long now = clock.millis();
            update(c, "INSERT INTO app_user(id,label,created_at) VALUES (?,?,?)", id, label.strip(), now);
            audit(c, "USER_CREATED", id, now);
            return user(c, id);
        });
    }
    public List<User> list() {
        return store.transaction(c -> {
            var result = new ArrayList<User>();
            try (var s = prepare(c, "SELECT * FROM app_user ORDER BY created_at,id"); var r = s.executeQuery()) {
                while (r.next()) result.add(readUser(r));
            }
            return List.copyOf(result);
        });
    }
    public User setEnabled(String userId, boolean enabled) {
        return store.transaction(c -> {
            User old = user(c, userId);
            if (old.enabled() == enabled) return old;
            ManagedConversations.invalidateWork(c, userId, clock.millis());
            update(c, "UPDATE app_user SET enabled=?,auth_epoch=auth_epoch+1 WHERE id=?", enabled ? 1 : 0, userId);
            // Disable revokes the epoch, not the saved credential. Resume may reuse it; a stale token is separately invalidated.
            update(c, "UPDATE binding_attempt SET phase='CANCELLED' WHERE user_id=? AND phase IN " + PENDING, userId);
            audit(c, enabled ? "USER_ENABLED" : "USER_DISABLED", userId, clock.millis());
            return user(c, userId);
        });
    }
    public Attempt begin(String userId, Mode mode) { return begin(userId, mode, null, null, false); }

    /** Browser mutations compare the displayed epoch and task; stale tabs cannot replace a newer invitation. */
    public Attempt beginChecked(String userId, Mode mode, long expectedEpoch, String previousAttempt) {
        return begin(userId, mode, expectedEpoch, previousAttempt, true);
    }
    private Attempt begin(String userId, Mode mode, Long expectedEpoch, String previousAttempt, boolean checked) {
        if (mode == null) throw new ManagedProblem(INVALID_INPUT);
        return store.transaction(c -> {
            User user = user(c, userId);
            if (!user.enabled()) throw new ManagedProblem(UNAUTHORIZED);
            boolean replacementRefresh = false;
            if (checked) {
                if (user.authEpoch() != expectedEpoch) throw new ManagedProblem(CONFLICT);
                var latest = latestAttempt(c, userId);
                if (previousAttempt == null) {
                    if (latest.isPresent() && OPEN_PHASES.contains(latest.get().phase())) throw new ManagedProblem(CONFLICT);
                } else {
                    if (latest.isEmpty() || !latest.get().id().equals(previousAttempt)
                            || latest.get().phase() == Phase.SUCCEEDED || latest.get().authEpoch() != user.authEpoch()
                            || latest.get().mode() != mode) throw new ManagedProblem(CONFLICT);
                    replacementRefresh = mode == Mode.REPLACE;
                }
            }
            long active = scalar(c, "SELECT count(*) FROM binding WHERE user_id=? AND active=1", userId);
            if ((mode == Mode.INITIAL && active != 0) || (mode != Mode.INITIAL && active != 1 && !replacementRefresh)) throw new ManagedProblem(CONFLICT);
            update(c, "UPDATE binding_attempt SET phase='CANCELLED' WHERE user_id=? AND phase IN " + PENDING, userId);
            if (mode == Mode.REPLACE && !replacementRefresh) {
                ManagedConversations.invalidateWork(c, userId, clock.millis());
                update(c, "UPDATE app_user SET auth_epoch=auth_epoch+1 WHERE id=?", userId);
                update(c, "UPDATE channel_session SET active=0 WHERE user_id=?", userId);
                update(c, "UPDATE binding SET active=0 WHERE user_id=?", userId);
                user = user(c, userId);
            }
            long now = clock.millis(); String id = UUID.randomUUID().toString();
            update(c, "INSERT INTO binding_attempt(id,user_id,auth_epoch,mode,phase,expires_at,created_at) VALUES (?,?,?,?,?,?,?)",
                    id, userId, user.authEpoch(), mode.name(), Phase.REQUESTING_QR.name(), now + Duration.ofMinutes(8).toMillis(), now);
            audit(c, "BINDING_" + mode.name(), userId + "/" + id, now);
            return attempt(c, userId, id);
        });
    }
    public Attempt attempt(String userId, String attemptId) { return store.transaction(c -> attempt(c, userId, attemptId)); }
    public void cancel(String userId, String attemptId) {
        store.transaction(c -> {
            attempt(c, userId, attemptId);
            int changed = update(c, "UPDATE binding_attempt SET phase='CANCELLED' WHERE id=? AND user_id=? AND phase IN " + PENDING, attemptId, userId);
            if (changed != 0) audit(c, "BINDING_CANCELLED", userId + "/" + attemptId, clock.millis());
            return null;
        });
    }
    public Attempt advance(String userId, String attemptId, Phase expected, Phase next) {
        if (next == null || expected == null || next == Phase.SUCCEEDED || !OPEN_PHASES.contains(expected)) throw new ManagedProblem(INVALID_INPUT);
        return store.transaction(c -> {
            Attempt attempt = liveAttempt(c, userId, attemptId);
            if (attempt.phase() != expected) throw new ManagedProblem(CONFLICT);
            update(c, "UPDATE binding_attempt SET phase=? WHERE id=?", next.name(), attemptId);
            if (!OPEN_PHASES.contains(next)) bindingAudit(c, userId, attemptId, next);
            return attempt(c, userId, attemptId);
        });
    }

    /** Only a resolver-backed identity, never a DTO supplied by a management client. */
    public ManagedScope activate(String userId, String attemptId, VerifiedIdentity identity, Credentials credentials) {
        Objects.requireNonNull(identity); Objects.requireNonNull(credentials);
        if (!identity.botId().equals(credentials.botId()) || credentials.origin() == null
                || !identity.origin().resolve("/").equals(credentials.origin().resolve("/"))
                || credentials.token() == null || credentials.token().isBlank()
                || credentials.token().contains("\n") || credentials.token().contains("\r")) throw new ManagedProblem(UNAUTHORIZED);
        return store.transaction(c -> {
            Attempt previous = attempt(c, userId, attemptId);
            if (previous.phase() == Phase.SUCCEEDED) {
                ManagedScope active = scope(c, userId);
                if (active.authEpoch() != previous.authEpoch() || !active.bindingId().equals(previous.completedBindingId())) throw new ManagedProblem(CONFLICT);
                if (scalar(c, "SELECT count(*) FROM binding b JOIN channel_session s ON s.binding_id=b.id WHERE b.id=? AND b.identity_namespace=? AND b.account_id=? AND b.bot_id=? AND b.sender_id=? AND s.token=?",
                        active.bindingId(), identity.namespace(), identity.accountId(), identity.botId(), identity.senderId(), credentials.token()) != 1) throw new ManagedProblem(CONFLICT);
                return active;
            }
            Attempt attempt = liveAttempt(c, userId, attemptId);
            if (attempt.phase() != Phase.VERIFYING_IDENTITY) throw new ManagedProblem(CONFLICT);
            String bindingId;
            if (attempt.mode() == Mode.REAUTHENTICATE) {
                ManagedConversations.invalidateWork(c, userId, clock.millis());
                try (var s = prepare(c, "SELECT * FROM binding WHERE user_id=? AND active=1", userId); var r = s.executeQuery()) {
                    if (!r.next() || !identity.namespace().equals(r.getString("identity_namespace"))
                            || !identity.accountId().equals(r.getString("account_id")) || !identity.botId().equals(r.getString("bot_id"))
                            || !identity.senderId().equals(r.getString("sender_id"))) throw new ManagedProblem(UNAUTHORIZED);
                    bindingId = r.getString("id");
                }
                update(c, "UPDATE binding SET origin=?,evidence_revision=? WHERE id=?", identity.origin().toString(), identity.evidenceRevision(), bindingId);
                update(c, "UPDATE channel_session SET token=?,scan_user_id=?,generation=generation+1,active=1,updated_at=? WHERE binding_id=?", credentials.token(), credentials.scanUserId(), clock.millis(), bindingId);
            } else {
                bindingId = UUID.randomUUID().toString();
                long version = scalar(c, "SELECT COALESCE(max(version),0)+1 FROM binding WHERE user_id=?", userId);
                update(c, "INSERT INTO binding(id,user_id,version,identity_namespace,account_id,bot_id,sender_id,origin,evidence_revision,active,created_at) VALUES (?,?,?,?,?,?,?,?,?,1,?)",
                        bindingId, userId, version, identity.namespace(), identity.accountId(), identity.botId(), identity.senderId(), identity.origin().toString(), identity.evidenceRevision(), clock.millis());
                update(c, "INSERT INTO channel_session(binding_id,user_id,token,scan_user_id,generation,active,updated_at) VALUES (?,?,?,?,1,1,?)", bindingId, userId, credentials.token(), credentials.scanUserId(), clock.millis());
            }
            update(c, "UPDATE binding_attempt SET phase='SUCCEEDED',completed_binding_id=? WHERE id=?", bindingId, attemptId);
            audit(c, "BINDING_ACTIVATED", userId + "/" + attemptId, clock.millis());
            return scope(c, userId);
        });
    }
    public record Overview(String id, String label, boolean enabled, long authEpoch, long createdAt,
                           boolean bound, boolean sessionActive, Long lastActivity, Attempt currentAttempt) { }
    public List<Overview> overview() { return overviews(null); }
    public Overview overview(String userId) { return overviews(Objects.requireNonNull(userId)).getFirst(); }
    private List<Overview> overviews(String userId) {
        return store.transaction(c -> {
            if (userId != null) user(c, userId);
            var rows = new ArrayList<Overview>();
            try (var s = prepare(c, "SELECT u.*,b.id binding_id,COALESCE(s.active,0) session_active,"
                    + "(SELECT max(v.last_user_message_at) FROM conversation v WHERE v.user_id=u.id AND v.binding_id=b.id) last_activity "
                    + "FROM app_user u LEFT JOIN binding b ON b.user_id=u.id AND b.active=1 LEFT JOIN channel_session s ON s.binding_id=b.id AND s.user_id=u.id "
                    + (userId == null ? "" : "WHERE u.id=? ") + "ORDER BY u.created_at,u.id", userId == null ? new Object[0] : new Object[]{userId}); var r = s.executeQuery()) {
                while (r.next()) rows.add(new Overview(r.getString("id"), r.getString("label"), r.getInt("enabled") == 1,
                        r.getLong("auth_epoch"), r.getLong("created_at"), r.getString("binding_id") != null,
                        r.getInt("session_active") == 1, nullableLong(r, "last_activity"), latestAttempt(c, r.getString("id")).orElse(null)));
            }
            return List.copyOf(rows);
        });
    }
    public Optional<Attempt> latestAttempt(String userId) {
        return store.transaction(c -> { user(c, userId); return latestAttempt(c, userId); });
    }
    private static Optional<Attempt> latestAttempt(Connection c, String userId) throws SQLException {
        try (var s = prepare(c, "SELECT id FROM binding_attempt WHERE user_id=? ORDER BY rowid DESC LIMIT 1", userId); var r = s.executeQuery()) {
            return r.next() ? Optional.of(attempt(c, userId, r.getString(1))) : Optional.empty();
        }
    }
    /** Safe terminalization, including expiration (advance intentionally rejects expired work). */
    public Attempt endAttempt(String userId, String attemptId, Phase terminal) {
        if (terminal == null || terminal == Phase.SUCCEEDED || OPEN_PHASES.contains(terminal)) throw new ManagedProblem(INVALID_INPUT);
        return store.transaction(c -> {
            attempt(c, userId, attemptId);
            if (update(c, "UPDATE binding_attempt SET phase=? WHERE id=? AND user_id=? AND phase IN " + PENDING,
                    terminal.name(), attemptId, userId) != 0) bindingAudit(c, userId, attemptId, terminal);
            return attempt(c, userId, attemptId);
        });
    }
    public Attempt checkAttempt(String userId, String attemptId) { return store.transaction(c -> liveAttempt(c, userId, attemptId)); }
    private void bindingAudit(Connection c, String userId, String attemptId, Phase terminal) throws SQLException {
        update(c, "INSERT INTO audit_event(actor,action,target,result,occurred_at) VALUES ('administrator','BINDING_FINISHED',?,?,?)",
                userId + "/" + attemptId, terminal.name(), clock.millis());
    }
    /** Only enabled users with current credentials; never exposes credentials to the admin DTO. */
    public List<ManagedScope> activeScopes() {
        return store.transaction(c -> {
            var result = new ArrayList<ManagedScope>();
            try (var s = prepare(c, "SELECT u.id FROM app_user u JOIN binding b ON b.user_id=u.id AND b.active=1 JOIN channel_session s ON s.binding_id=b.id AND s.user_id=u.id WHERE u.enabled=1 AND s.active=1"); var r = s.executeQuery()) {
                while (r.next()) result.add(scope(c, r.getString(1)));
            }
            return List.copyOf(result);
        });
    }
    /** Late failures from a retired connection cannot revoke its successor. */
    public boolean invalidateSession(ManagedScope expected) {
        return store.transaction(c -> {
            try { authorize(c, expected); }
            catch (ManagedProblem rejected) { if (rejected.code() == UNAUTHORIZED) return false; throw rejected; }
            ManagedConversations.invalidateWork(c, expected.userId(), clock.millis());
            update(c, "UPDATE channel_session SET active=0 WHERE user_id=? AND binding_id=? AND generation=?", expected.userId(), expected.bindingId(), expected.generation());
            update(c, "UPDATE app_user SET auth_epoch=auth_epoch+1 WHERE id=?", expected.userId());
            update(c, "UPDATE binding_attempt SET phase='CANCELLED' WHERE user_id=? AND phase IN " + PENDING, expected.userId());
            audit(c, "SESSION_EXPIRED", expected.userId(), clock.millis());
            return true;
        });
    }
    public ManagedScope scope(String userId) { return store.transaction(c -> scope(c, userId)); }
    public ConnectionState connection(ManagedScope scope) {
        return store.transaction(c -> {
            authorize(c, scope);
            try (var s = prepare(c, "SELECT s.token,s.cursor,s.scan_user_id,b.origin FROM channel_session s JOIN binding b ON b.id=s.binding_id WHERE s.binding_id=? AND s.user_id=?", scope.bindingId(), scope.userId()); var r = s.executeQuery()) {
                if (!r.next()) throw new ManagedProblem(UNAUTHORIZED);
                return new ConnectionState(scope, new Credentials(scope.botId(), r.getString("token"), java.net.URI.create(r.getString("origin")), r.getString("scan_user_id")), r.getString("cursor"));
            }
        });
    }
    static void authorize(Connection c, ManagedScope expected) throws SQLException {
        if (expected == null || !expected.equals(scope(c, expected.userId()))) throw new ManagedProblem(UNAUTHORIZED);
    }
    static ManagedScope scope(Connection c, String userId) throws SQLException {
        try (var s = prepare(c, "SELECT b.id,b.bot_id,b.sender_id,s.generation,u.auth_epoch FROM app_user u JOIN binding b ON b.user_id=u.id AND b.active=1 JOIN channel_session s ON s.binding_id=b.id AND s.user_id=u.id AND s.active=1 WHERE u.id=? AND u.enabled=1", userId); var r = s.executeQuery()) {
            if (!r.next()) throw new ManagedProblem(UNAUTHORIZED);
            return new ManagedScope(userId, r.getString("id"), r.getString("bot_id"), r.getString("sender_id"), r.getLong("generation"), r.getLong("auth_epoch"));
        }
    }
    static User user(Connection c, String id) throws SQLException {
        try (var s = prepare(c, "SELECT * FROM app_user WHERE id=?", id); var r = s.executeQuery()) {
            if (!r.next()) throw new ManagedProblem(NOT_FOUND); return readUser(r);
        }
    }
    private static User readUser(ResultSet r) throws SQLException { return new User(r.getString("id"), r.getString("label"), r.getInt("enabled") == 1, r.getLong("auth_epoch"), r.getLong("created_at")); }
    private Attempt liveAttempt(Connection c, String userId, String id) throws SQLException {
        Attempt attempt = attempt(c, userId, id); User user = user(c, userId);
        if (!user.enabled() || user.authEpoch() != attempt.authEpoch()) throw new ManagedProblem(UNAUTHORIZED);
        if (clock.millis() >= attempt.expiresAt()) throw new ManagedProblem(EXPIRED);
        if (!OPEN_PHASES.contains(attempt.phase())) throw new ManagedProblem(CONFLICT);
        return attempt;
    }
    private static Attempt attempt(Connection c, String userId, String id) throws SQLException {
        try (var s = prepare(c, "SELECT * FROM binding_attempt WHERE id=? AND user_id=?", id, userId); var r = s.executeQuery()) {
            if (!r.next()) throw new ManagedProblem(NOT_FOUND);
            return new Attempt(id, userId, r.getLong("auth_epoch"), Mode.valueOf(r.getString("mode")), Phase.valueOf(r.getString("phase")), r.getLong("expires_at"), r.getString("completed_binding_id"));
        }
    }
}