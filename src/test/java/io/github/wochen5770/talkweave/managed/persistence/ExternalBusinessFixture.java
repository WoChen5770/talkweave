package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.managed.config.ExternalIntegrationTarget;
import java.sql.*;
import java.util.*;

/** Explicit, exclusive synthetic business fixture. Never resets a schema, sequence, or shared service. */
public final class ExternalBusinessFixture implements AutoCloseable {
    private ExternalIntegrationTarget target;
    private ManagedStore current;
    private long epoch;

    public ManagedStore open() {
        if (target == null) target = ExternalIntegrationTarget.load();
        target.requireSchemaInitialization(); target.requireSchemaUpgrade();
        var owner = MysqlOwnership.acquire(target.config().mysql());
        try {
            owner.use(c -> {
                target.verifyMysql(c);
                if (epoch == 0) target.requireNoBusinessData(c);
                else if (Sql.scalar(c, "SELECT epoch FROM runtime_owner WHERE slot=1") != epoch)
                    throw new IllegalStateException("Fixture ownership changed; refusing access");
                return null;
            });
            current = ManagedStore.openOwned(target.config().mysql(), owner);
            epoch = current.epoch();
            return current;
        } catch (SQLException | RuntimeException failure) {
            owner.close(); throw new IllegalStateException("Synthetic fixture initialization failed; target unchanged except recognized initialization");
        }
    }

    @Override public void close() {
        if (epoch == 0) return;
        if (current != null) current.close();
        try (var owner = MysqlOwnership.acquire(target.config().mysql())) {
            owner.use(c -> {
                target.verifyMysql(c);
                if (Sql.scalar(c, "SELECT epoch FROM runtime_owner WHERE slot=1") != epoch)
                    throw new SQLException("Fixture ownership changed");
                // Initial emptiness + uninterrupted application epochs identify this fixture's writes.
                // Snapshot exact primary keys under exclusive ownership, then remove only those keys.
                // No TRUNCATE, DROP, sequence reset, wildcard DELETE, or foreign-key disabling.
                var keys = new LinkedHashMap<String, List<Object[]>>();
                var columns = new LinkedHashMap<String, String>();
                columns.put("model_usage", "attempt_id"); columns.put("model_attempt", "id");
                columns.put("turn", "event_sequence"); columns.put("inbound_event", "sequence");
                columns.put("active_conversation", "user_id,binding_id"); columns.put("conversation", "id");
                columns.put("active_invitation", "user_id"); columns.put("binding_attempt", "id");
                columns.put("channel_session", "binding_id"); columns.put("active_binding", "user_id");
                columns.put("binding_connection", "binding_id,user_id,bot_id,sender_id"); columns.put("binding", "id");
                columns.put("app_user", "id"); columns.put("administrator", "slot");
                columns.put("audit_event", "sequence"); columns.put("model_configuration", "version");
                c.setAutoCommit(false);
                try {
                    for (var entry : columns.entrySet()) {
                        var values = new ArrayList<Object[]>();
                        try (var s = c.createStatement(); var r = s.executeQuery("SELECT " + entry.getValue() + " FROM " + entry.getKey() + " FOR UPDATE")) {
                            while (r.next()) {
                                var row = new Object[entry.getValue().split(",").length];
                                for (int i = 0; i < row.length; i++) row[i] = r.getObject(i + 1);
                                values.add(row);
                            }
                        }
                        keys.put(entry.getKey(), values);
                    }
                    // Only the singleton known empty at fixture entry; never a production settings row.
                    Sql.update(c, "UPDATE admin_setting SET idle_minutes=30,revision=1,model_version=NULL WHERE slot=1");
                    for (var entry : keys.entrySet()) {
                        String predicate = String.join(" AND ", Arrays.stream(columns.get(entry.getKey()).split(",")).map(k -> k + "=?").toList());
                        for (var row : entry.getValue()) Sql.update(c, "DELETE FROM " + entry.getKey() + " WHERE " + predicate, row);
                    }
                    owner.check(); c.commit();
                } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
                finally { c.setAutoCommit(true); }
                target.requireNoBusinessData(c);
                return null;
            });
            epoch = 0;
        } catch (SQLException | RuntimeException failure) {
            throw new IllegalStateException("Synthetic fixture cleanup failed; no schema reset attempted");
        }
    }
}
