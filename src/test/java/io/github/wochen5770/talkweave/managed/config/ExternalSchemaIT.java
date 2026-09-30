package io.github.wochen5770.talkweave.managed.config;

import io.github.wochen5770.talkweave.managed.persistence.*;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit authorized initialization only. Inserts are rolled back; never drop/truncate persistent tables. */
class ExternalSchemaIT {
    @Test void initializeAndValidateDedicatedSchema() {
        String phase = "CONFIG";
        try {
            var target = ExternalIntegrationTarget.load();
            target.requireSchemaInitialization();
            target.requireSchemaUpgrade();
            var config = target.config();
            phase = "SCHEMA_OPEN";
            MysqlLayout.Opened opened;
            long priorVersion;
            String priorInstallation;
            long priorEpoch;
            long nextConversationSequence;
            try (var owner = MysqlOwnership.acquire(config.mysql())) {
                try (var preflight = MysqlConnections.dedicated(config.mysql())) {
                    target.verifyMysql(preflight);
                    target.requireNoBusinessData(preflight);
                    priorVersion = scalar(preflight, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='managed_schema'") == 0
                            ? 0 : scalar(preflight, "SELECT version FROM managed_schema WHERE slot=1");
                    priorInstallation = priorVersion == 0 ? null : string(preflight, "SELECT installation_id FROM managed_schema WHERE slot=1");
                    priorEpoch = priorVersion == 0 ? 0 : scalar(preflight, "SELECT epoch FROM runtime_owner WHERE slot=1");
                }
                // Refuse a competing process before migration as well as after it.
                assertEquals(ManagedProblem.Code.DIRECTORY_IN_USE,
                        assertThrows(ManagedProblem.class, () -> MysqlOwnership.acquire(config.mysql())).code());
                phase = "LAYOUT_OPEN_OR_UPGRADE";
                opened = MysqlLayout.open(owner);
                if (priorInstallation != null) assertEquals(priorInstallation, opened.installationId());
                assertEquals(priorEpoch + 1, opened.epoch());
                phase = "SECOND_INSTANCE";
                var conflict = assertThrows(ManagedProblem.class, () -> MysqlOwnership.acquire(config.mysql()));
                assertEquals(ManagedProblem.Code.DIRECTORY_IN_USE, conflict.code());
                owner.check();
                phase = "CONSTRAINTS_AND_BIGINT";
                try (var pool = MysqlConnections.pool(config.mysql()); var connection = pool.getConnection()) {
                    assertEquals(opened.epoch(), scalar(connection, "SELECT epoch FROM runtime_owner WHERE slot=1"));
                    assertEquals(MysqlLayout.VERSION, scalar(connection, "SELECT version FROM managed_schema WHERE slot=1"));
                    connection.setAutoCommit(false);
                    try { constraints(connection, opened.epoch(), target.fixtureLabel()); }
                    finally { connection.rollback(); connection.setAutoCommit(true); }
                    assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM app_user WHERE label=?", target.fixtureLabel()));
                    nextConversationSequence = scalar(connection,
                            "SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='conversation'");
                }
            }
            phase = "REOPEN";
            try (var owner = MysqlOwnership.acquire(config.mysql())) {
                var reopened = MysqlLayout.open(owner);
                assertEquals(opened.installationId(), reopened.installationId());
                assertEquals(opened.epoch() + 1, reopened.epoch());
                try (var check = MysqlConnections.dedicated(config.mysql())) {
                    assertEquals(MysqlLayout.VERSION, scalar(check, "SELECT version FROM managed_schema WHERE slot=1"));
                    assertEquals(nextConversationSequence, scalar(check,
                            "SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='conversation'"));
                }
            }
            System.out.println("NAS SCHEMA tables=20 fromVersion=" + priorVersion + " toVersion=" + MysqlLayout.VERSION
                    + " reopen=PASS secondInstance=REJECTED constraints=PASS fixtureRows=ROLLED_BACK");
        } catch (Throwable failure) {
            String code = failure instanceof ManagedProblem problem ? problem.code().name()
                    : failure instanceof SQLException sql ? "SQL_" + sql.getErrorCode() : failure.getClass().getSimpleName();
            fail("NAS schema check failed: " + phase + " (" + code + ")");
        }
    }

    private static void constraints(Connection c, long epoch, String label) throws SQLException {
        String a = id(), b = id(), d = id(), bindingA = id(), bindingB = id(), bindingD = id();
        for (String user : new String[]{a,b,d})
            update(c, "INSERT INTO app_user(id,label,created_at) VALUES (?,?,0)", user,label);
        binding(c, bindingA, a, "Case", "bot-a"); binding(c, bindingB, b, "case", "bot-b");
        binding(c, bindingD, d, "case ", "bot-d");
        // Binary NO PAD identity comparison distinguishes case and trailing spaces.
        active(c, bindingA, a, "Case", "bot-a"); active(c, bindingB, b, "case", "bot-b");
        active(c, bindingD, d, "case ", "bot-d");
        assertEquals(3, scalar(c, "SELECT COUNT(*) FROM active_binding WHERE user_id IN (?,?,?)", a,b,d));
        String other = id(); binding(c, other, b, "Case", "bot-other", 2);
        constraint(() -> active(c, other, b, "Case", "bot-other")); // occupied user/account
        constraint(() -> active(c, bindingA, b, "Case", "bot-a")); // wrong scope
        String e = id(), bindingE = id(), bindingF = id();
        update(c, "INSERT INTO app_user(id,label,created_at) VALUES (?,?,0)", e,label);
        binding(c,bindingE,e,"Case","bot-e");
        constraint(() -> active(c,bindingE,e,"Case","bot-e")); // same account, otherwise free user/bot
        binding(c,bindingF,e,"different","bot-a",2);
        constraint(() -> active(c,bindingF,e,"different","bot-a")); // same bot, otherwise free user/account
        String invitation = id();
        update(c, "INSERT INTO binding_attempt(id,user_id,auth_epoch,mode,phase,expires_at,created_at) VALUES (?,?,1,'INITIAL','REQUESTING_QR',1,0)", invitation,a);
        update(c, "INSERT INTO active_invitation VALUES (?,?)", a,invitation);
        constraint(() -> update(c, "INSERT INTO active_invitation VALUES (?,?)", b,invitation));
        String invitation2 = id();
        update(c, "INSERT INTO binding_attempt(id,user_id,auth_epoch,mode,phase,expires_at,created_at) VALUES (?,?,1,'INITIAL','REQUESTING_QR',1,0)", invitation2,a);
        constraint(() -> update(c, "INSERT INTO active_invitation VALUES (?,?)", a,invitation2));
        String conversation = id();
        update(c, "INSERT INTO conversation(id,user_id,binding_id,created_at) VALUES (?,?,?,0)", conversation,a,bindingA);
        update(c, "INSERT INTO active_conversation VALUES (?,?,?)", a,bindingA,conversation);
        constraint(() -> update(c, "INSERT INTO active_conversation VALUES (?,?,?)", b,bindingB,conversation));
        String conversation2 = id();
        update(c, "INSERT INTO conversation(id,user_id,binding_id,created_at) VALUES (?,?,?,0)", conversation2,a,bindingA);
        long firstSequence = scalar(c, "SELECT sequence FROM conversation WHERE id=?", conversation);
        long secondSequence = scalar(c, "SELECT sequence FROM conversation WHERE id=?", conversation2);
        assertTrue(firstSequence > 0 && secondSequence > firstSequence);
        assertEquals(firstSequence, scalar(c, "SELECT sequence FROM conversation WHERE user_id=? AND sequence<? ORDER BY sequence DESC LIMIT 1", a,secondSequence));
        constraint(() -> update(c, "INSERT INTO active_conversation VALUES (?,?,?)", a,bindingA,conversation2));
        long large = 9_007_199_254_740_993L;
        update(c, "UPDATE conversation SET history_revision=?,confirmed_turn_count=?,last_confirmed_sequence=? WHERE id=?", large,large,large,conversation);
        assertEquals(large, scalar(c, "SELECT history_revision FROM conversation WHERE id=?", conversation));
        String text = "原文🙂e\u0301\nSELECT 保持原样";
        long sequence;
        try (var insert = c.prepareStatement("INSERT INTO inbound_event(user_id,binding_id,conversation_id,bot_id,sender_id,generation,auth_epoch,message_id,received_at,text,context_token,kind) VALUES (?,?,?,'bot-a','sender',1,1,?,0,?,'synthetic','CHAT')", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1,a); insert.setString(2,bindingA); insert.setString(3,conversation);
            insert.setString(4,id()); insert.setString(5,text); insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) { assertTrue(keys.next()); sequence = keys.getLong(1); }
        }
        update(c, "INSERT INTO turn(event_sequence,user_id,binding_id,conversation_id,runtime_epoch,stage,reply_text,updated_at) VALUES (?,?,?,?,?,'SENT',?,0)", sequence,a,bindingA,conversation,epoch,text);
        try (var statement = c.prepareStatement("SELECT e.text,t.reply_text FROM inbound_event e JOIN turn t ON t.event_sequence=e.sequence WHERE e.sequence=?")) {
            statement.setLong(1,sequence);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(text,rows.getString(1)); assertEquals(text,rows.getString(2)); }
        }
        constraint(() -> update(c, "UPDATE turn SET user_id=? WHERE event_sequence=?", b,sequence));
        long model;
        try (var insert = c.prepareStatement("INSERT INTO model_configuration(configuration_json,created_at) VALUES ('{}',0)", Statement.RETURN_GENERATED_KEYS)) {
            insert.executeUpdate(); try (var keys = insert.getGeneratedKeys()) { assertTrue(keys.next()); model = keys.getLong(1); }
        }
        String attempt = id();
        update(c, "INSERT INTO model_attempt(id,event_sequence,user_id,binding_id,conversation_id,runtime_epoch,model_version,attempt_number,started_at,outcome) VALUES (?,?,?,?,?,?,?,1,0,'SUCCEEDED')", attempt,sequence,a,bindingA,conversation,epoch,model);
        update(c, "INSERT INTO model_usage VALUES (?,?,?,?,'REPORTED')", attempt,Long.MAX_VALUE,large,large);
        assertEquals(Long.MAX_VALUE, scalar(c, "SELECT input_tokens FROM model_usage WHERE attempt_id=?", attempt));
        constraint(() -> update(c, "UPDATE model_usage SET input_tokens=1 WHERE attempt_id=?", attempt));
        constraint(() -> update(c, "UPDATE model_attempt SET binding_id=? WHERE id=?", bindingB,attempt));
        update(c, "INSERT INTO audit_event(actor,action,target,result,occurred_at) VALUES ('synthetic','CHECK',?,'SUCCEEDED',?)", a,large);
        update(c, "INSERT INTO administrator VALUES (1,'synthetic','not-a-real-password-hash',0)");
        constraint(() -> update(c, "INSERT INTO administrator VALUES (2,'synthetic-2','not-a-real-password-hash',0)"));
    }

    private static void binding(Connection c, String id, String user, String account, String bot) throws SQLException {
        binding(c,id,user,account,bot,1);
    }
    private static void binding(Connection c, String id, String user, String account, String bot, long version) throws SQLException {
        update(c, "INSERT INTO binding(id,user_id,version,identity_namespace,account_id,bot_id,sender_id,origin,evidence_revision,created_at) VALUES (?,?,?,'synthetic',?,?,'sender','https://example.invalid','synthetic',0)", id,user,version,account,bot);
        update(c, "INSERT INTO binding_connection VALUES (?,?,?,'sender')", id,user,bot);
    }
    private static void active(Connection c, String binding, String user, String account, String bot) throws SQLException {
        update(c, "INSERT INTO active_binding VALUES (?,?,'synthetic',?,?,'sender')", user,binding,account,bot);
    }
    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }
    private static void constraint(SqlAction action) {
        var failure = assertThrows(SQLException.class, action::run);
        assertTrue("23000".equals(failure.getSQLState()) || failure.getErrorCode() == 3819);
    }
    private static int update(Connection c, String sql, Object... values) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            for (int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            return statement.executeUpdate();
        }
    }
    private static long scalar(Connection c, String sql, Object... values) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            for (int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            try (var rows=statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
    private static String string(Connection c, String sql) throws SQLException {
        try (var statement=c.createStatement(); var rows=statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
    private static String id() { return UUID.randomUUID().toString(); }
}
