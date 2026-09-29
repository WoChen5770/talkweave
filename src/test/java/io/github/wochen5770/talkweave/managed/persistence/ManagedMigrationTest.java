package io.github.wochen5770.talkweave.managed.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ManagedMigrationTest {
    @TempDir Path directory;
    private static final List<String> TABLES = List.of("administrator", "app_user", "binding", "channel_session",
            "binding_attempt", "conversation", "inbound_event", "turn", "model_configuration", "admin_setting",
            "model_attempt", "model_usage", "audit_event");

    @Test void v1UpgradePreservesEveryRecordAndAllowsReauthenticationWithHistory() throws Exception {
        Map<String, List<List<Object>>> before;
        try (var c = v1()) { seed(c); Sql.update(c, "UPDATE sqlite_sequence SET seq=700 WHERE name='inbound_event'"); before = rows(c); }
        try (var store = ManagedStore.open(directory)) {
            assertThat(store.transaction(ManagedMigrationTest::rows)).isEqualTo(before);
            store.transaction(c -> {
                assertThat(Sql.scalar(c, "PRAGMA user_version")).isEqualTo(2);
                assertThat(Sql.scalar(c, "SELECT version FROM managed_schema")).isEqualTo(2);
                assertThat(Sql.scalar(c, "PRAGMA foreign_keys")).isEqualTo(1);
                assertThat(Sql.scalar(c, "SELECT count(*) FROM binding_connection")).isEqualTo(1);
                assertThat(Sql.scalar(c, "SELECT seq FROM sqlite_sequence WHERE name='inbound_event'")).isEqualTo(700);
                try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA foreign_key_check")) { assertThat(r.next()).isFalse(); }
                return null;
            });
            var users = new ManagedUsers(store, Clock.systemUTC());
            var task = users.beginChecked("user-a", ManagedUsers.Mode.REAUTHENTICATE, 1, null);
            users.advance("user-a", task.id(), ManagedUsers.Phase.REQUESTING_QR, ManagedUsers.Phase.VERIFYING_IDENTITY);
            var origin = java.net.URI.create("https://ilinkai.weixin.qq.com");
            var id = new io.github.wochen5770.talkweave.channel.wechat.ScannerIdentityResolver.VerifiedIdentity(
                    "test", "account-a", "bot-new", "sender-a", origin, "test-only");
            var creds = new io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.Credentials("bot-new", "fake-new-token", origin, "sender-a");
            var renewed = users.activate("user-a", task.id(), id, creds);
            assertThat(renewed.bindingId()).isEqualTo("binding-a");
            assertThat(users.connection(renewed).cursor()).isEmpty();
            store.transaction(c -> {
                assertThat(Sql.scalar(c, "SELECT count(*) FROM inbound_event WHERE bot_id='bot-old' AND text='private-history'")).isEqualTo(1);
                assertThat(Sql.scalar(c, "SELECT count(*) FROM binding_connection")).isEqualTo(2);
                assertThat(Sql.scalar(c, "SELECT input_tokens FROM model_usage")).isEqualTo(20);
                return null;
            });
            assertThatThrownBy(() -> store.transaction(c -> {
                Sql.update(c, "UPDATE inbound_event SET bot_id='unrelated-bot' WHERE sequence=7"); return null;
            })).isInstanceOf(ManagedProblem.class);
        }
        try (var store = ManagedStore.open(directory)) {
            assertThat(new ManagedUsers(store, Clock.systemUTC()).scope("user-a").botId()).isEqualTo("bot-new");
            assertThat(store.<Long>transaction(c -> Sql.scalar(c, "SELECT count(*) FROM binding_connection"))).isEqualTo(2L);
        }
    }

    @Test void failedMigrationRollsBackDdlDataAndVersionAndRestoresEnforcement() throws Exception {
        try (var c = v1(); var s = c.createStatement()) {
            seed(c);
            s.execute("CREATE TABLE inbound_event_new (keep TEXT)");
            var before = rows(c);
            assertThatThrownBy(() -> ManagedStore.migrate(c)).isInstanceOf(SQLException.class);
            assertThat(rows(c)).isEqualTo(before);
            assertThat(Sql.scalar(c, "PRAGMA user_version")).isEqualTo(1);
            assertThat(Sql.scalar(c, "PRAGMA foreign_keys")).isEqualTo(1);
            assertThat(Sql.scalar(c, "SELECT count(*) FROM sqlite_master WHERE name='binding_connection'")).isZero();
            s.execute("DROP TABLE inbound_event_new");
            ManagedStore.migrate(c);
            assertThat(Sql.scalar(c, "PRAGMA user_version")).isEqualTo(2);
        }
    }

    private Connection v1() throws Exception {
        Files.writeString(directory.resolve(ManagedStore.MARKER), "talkweave-managed:1\n");
        var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(ManagedStore.DATABASE).toUri());
        try (var in = ManagedStore.class.getResourceAsStream("/db/managed/V001__managed.sql"); var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            for (String sql : new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8).split(";"))
                if (!sql.isBlank()) s.execute(sql);
        }
        return c;
    }
    private static void seed(Connection c) throws SQLException {
        String[] statements = {
            "INSERT INTO administrator VALUES(1,'admin','fake-hash',1)",
            "INSERT INTO app_user VALUES('user-a','label',1,1,1)",
            "INSERT INTO binding VALUES('binding-a','user-a',1,'test','account-a','bot-old','sender-a','https://ilinkai.weixin.qq.com','test-only',1,1)",
            "INSERT INTO channel_session VALUES('binding-a','user-a','fake-token','sender-a',1,'old-cursor',1,1)",
            "INSERT INTO binding_attempt VALUES('attempt-a','user-a',1,'INITIAL','SUCCEEDED',100,1,'binding-a')",
            "INSERT INTO conversation VALUES('chat-a','user-a','binding-a',1,1,NULL,1)",
            "INSERT INTO inbound_event VALUES(7,'user-a','binding-a','chat-a','bot-old','sender-a',1,1,'message-a',1,'private-history','fake-context','CHAT')",
            "INSERT INTO turn VALUES(7,'user-a','binding-a','chat-a','SENT','private-reply',1,'client-a',1)",
            "INSERT INTO model_configuration VALUES(1,'{}',1)",
            "UPDATE admin_setting SET idle_minutes=42,model_version=1",
            "INSERT INTO model_attempt VALUES('model-a',7,'user-a','binding-a','chat-a',1,1,1,'SUCCEEDED')",
            "INSERT INTO model_usage VALUES('model-a',20,3,10,'REPORTED')",
            "INSERT INTO audit_event VALUES(1,'administrator','TEST','user-a','SUCCEEDED',1)"
        };
        try (var s = c.createStatement()) { for (String sql : statements) s.execute(sql); }
    }
    private static Map<String, List<List<Object>>> rows(Connection c) throws SQLException {
        var result = new LinkedHashMap<String, List<List<Object>>>();
        for (String table : TABLES) {
            var records = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
                while (r.next()) {
                    var record = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) record.add(r.getObject(i));
                    records.add(record);
                }
            }
            result.put(table, records);
        }
        return result;
    }
}
