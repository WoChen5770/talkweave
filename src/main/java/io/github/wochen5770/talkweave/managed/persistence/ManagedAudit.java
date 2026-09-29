package io.github.wochen5770.talkweave.managed.persistence;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import static io.github.wochen5770.talkweave.managed.persistence.Sql.*;

/** Structured audit only. Callers cannot supply arbitrary message/credential text. */
public final class ManagedAudit {
    public enum SecurityEvent { LOGIN_SUCCEEDED, LOGIN_FAILED, LOGIN_THROTTLED, LOGOUT }
    public record Entry(long sequence, String actor, String action, String target, String result, long occurredAt) { }
    private final ManagedStore store;
    private final Clock clock;
    public ManagedAudit(ManagedStore store, Clock clock) { this.store = store; this.clock = clock; }
    public void security(SecurityEvent event) {
        store.transaction(c -> {
            update(c, "INSERT INTO audit_event(actor,action,target,result,occurred_at) VALUES ('administrator',?,'administrator',?,?)",
                    event.name(), event == SecurityEvent.LOGIN_FAILED || event == SecurityEvent.LOGIN_THROTTLED ? "REJECTED" : "SUCCEEDED", clock.millis());
            return null;
        });
    }
    public List<Entry> page(long beforeExclusive, int limit) { return page(null, beforeExclusive, limit); }
    public List<Entry> page(String userId, long beforeExclusive, int limit) {
        if (beforeExclusive < 1 || limit < 1 || limit > 100) throw new ManagedProblem(ManagedProblem.Code.INVALID_INPUT);
        return store.transaction(c -> {
            if (userId != null) ManagedUsers.user(c, userId);
            var rows = new ArrayList<Entry>();
            try (var s = prepare(c, "SELECT * FROM audit_event WHERE sequence<? "
                    + (userId == null ? "" : "AND (target=? OR substr(target,1,length(?))=?) ")
                    + "ORDER BY sequence DESC LIMIT ?", userId == null ? new Object[]{beforeExclusive, limit}
                    : new Object[]{beforeExclusive, userId, userId + "/", userId + "/", limit}); var r = s.executeQuery()) {
                while (r.next()) rows.add(new Entry(r.getLong("sequence"), r.getString("actor"), r.getString("action"), r.getString("target"), r.getString("result"), r.getLong("occurred_at")));
            }
            return List.copyOf(rows);
        });
    }
}
