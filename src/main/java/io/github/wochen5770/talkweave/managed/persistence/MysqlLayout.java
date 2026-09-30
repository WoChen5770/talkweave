package io.github.wochen5770.talkweave.managed.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Fail-closed, versioned initialization. MySQL DDL is NOT transactionally rolled back. */
public final class MysqlLayout {
    public record Opened(String installationId, long epoch) { }
    public static final int VERSION = 2;
    private record Installed(long version, String installationId) { }
    private static final String SCRIPT = script("V001__managed.sql");
    private static final String UPGRADE = script("V002__conversation_pagination.sql");
    private static final String V1_HASH = MysqlOwnership.digest(SCRIPT);
    private static final String V2_HASH = MysqlOwnership.digest(SCRIPT + "\n" + UPGRADE);
    private static final Set<String> TABLES = Set.of("managed_schema", "runtime_owner", "capacity_guard", "administrator",
            "app_user", "binding", "binding_connection", "active_binding", "channel_session", "binding_attempt",
            "active_invitation", "conversation", "active_conversation", "inbound_event", "turn", "model_configuration",
            "admin_setting", "model_attempt", "model_usage", "audit_event");
    private MysqlLayout() { }

    /** Read-only metadata preflight; call before attempting schema ownership. */
    public static void inspect(Connection connection) {
        try {
            var tables = objects(connection);
            if (!tables.isEmpty()) validate(connection, tables);
        } catch (SQLException failure) { throw new ManagedProblem(INCOMPATIBLE_LAYOUT); }
    }

    /** Caller holds an exclusive physical session. Recheck after lock, before the first write. */
    public static Opened open(MysqlOwnership owner) {
        try {
            return owner.use(connection -> {
                var tables = objects(connection);
                if (tables.isEmpty()) initialize(connection, owner);
                Installed installed = validate(connection, objects(connection));
                if (installed.version() == 1) {
                    upgrade(connection, owner);
                    installed = validate(connection, objects(connection));
                }
                owner.check();
                connection.setAutoCommit(false);
                try {
                    if (Sql.update(connection, "UPDATE runtime_owner SET epoch=epoch+1 WHERE slot=1 AND epoch<?", Long.MAX_VALUE) != 1)
                        throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
                    long epoch = Sql.scalar(connection, "SELECT epoch FROM runtime_owner WHERE slot=1");
                    owner.check();
                    connection.commit();
                    return new Opened(installed.installationId(), epoch);
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback(); throw failure;
                } finally { connection.setAutoCommit(true); }
            });
        } catch (SQLException failure) { throw new ManagedProblem(INCOMPATIBLE_LAYOUT); }
    }

    private static void initialize(Connection connection, MysqlOwnership owner) throws SQLException {
        int index = 0;
        for (String statement : SCRIPT.split(";")) {
            if (statement.isBlank()) continue;
            owner.check();
            try (var sql = connection.createStatement()) { sql.execute(statement); }
            if (index++ == 0) {
                Sql.update(connection, "INSERT INTO managed_schema(slot,version,state,installation_id,script_sha256) VALUES (1,1,'INITIALIZING',?,?)",
                        UUID.randomUUID().toString(), V1_HASH);
            }
        }
        String digest = fingerprint(connection);
        owner.check();
        if (Sql.update(connection, "UPDATE managed_schema SET state='READY',layout_sha256=? WHERE slot=1 AND state='INITIALIZING'", digest) != 1)
            throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
    }

    /** Called only after exact READY V1 validation under ownership; never retry partial DDL. */
    static void upgrade(Connection connection, MysqlOwnership owner) throws SQLException {
        if (!connection.getAutoCommit()) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
        owner.check();
        // A separate durable marker is required: ALTER commits implicitly and cannot be rolled back.
        if (Sql.update(connection, "UPDATE managed_schema SET version=2,state='INITIALIZING',script_sha256=?,layout_sha256=NULL "
                + "WHERE slot=1 AND version=1 AND state='READY' AND script_sha256=?", V2_HASH, V1_HASH) != 1)
            throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
        owner.check();
        try (var statement = connection.createStatement()) { statement.execute(UPGRADE); }
        String digest = fingerprint(connection);
        owner.check();
        if (Sql.update(connection, "UPDATE managed_schema SET state='READY',layout_sha256=? "
                + "WHERE slot=1 AND version=2 AND state='INITIALIZING' AND script_sha256=?", digest, V2_HASH) != 1)
            throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
    }

    private static Installed validate(Connection connection, Set<String> tables) throws SQLException {
        if (!TABLES.equals(tables)) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT version,state,installation_id,script_sha256,layout_sha256 FROM managed_schema WHERE slot=1")) {
            if (!rows.next()) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
            long version = rows.getLong(1);
            validateMarker(version, rows.getString(2), rows.getString(4), rows.getString(5), fingerprint(connection));
            String installation = rows.getString(3);
            try { UUID.fromString(installation); }
            catch (RuntimeException invalid) { throw new ManagedProblem(INCOMPATIBLE_LAYOUT); }
            if (rows.next()) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
            return new Installed(version, installation);
        }
    }

    static void validateMarker(long version, String state, String scriptHash, String layoutHash, String actualLayout) {
        String expectedHash = version == 1 ? V1_HASH : version == VERSION ? V2_HASH : null;
        if (expectedHash == null || !"READY".equals(state) || !expectedHash.equals(scriptHash)
                || actualLayout == null || !actualLayout.equals(layoutHash)) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
    }

    private static Set<String> objects(Connection connection) throws SQLException {
        var names = new TreeSet<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT table_name,table_type,engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            while (rows.next()) {
                if (!"BASE TABLE".equals(rows.getString(2)) || !"InnoDB".equals(rows.getString(3))
                        || !"utf8mb4_0900_bin".equals(rows.getString(4))) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
                names.add(rows.getString(1));
            }
        }
        for (String sql : List.of("SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE()",
                "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()",
                "SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE()")) {
            if (Sql.scalar(connection, sql) != 0) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
        }
        return names;
    }

    private static String fingerprint(Connection connection) throws SQLException {
        var parts = new StringBuilder();
        // SHOW CREATE captures types, collations, constraints, indexes and their exact definitions.
        // AUTO_INCREMENT is data, not layout, and changes on inserts (including rolled-back ones).
        for (String table : new TreeSet<>(TABLES)) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                if (!rows.next()) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
                parts.append(rows.getString(2).replaceAll(" AUTO_INCREMENT=\\d+", "")).append('\n');
            }
        }
        return MysqlOwnership.digest(parts.toString());
    }

    private static String script(String file) {
        try (var input = MysqlLayout.class.getResourceAsStream("/db/mysql/" + file)) {
            if (input == null) throw new IOException();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) { throw new IllegalStateException("MySQL schema unavailable"); }
    }
}
