package io.github.personalassistant.persistence;

import io.github.personalassistant.support.TestProperties;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SchemaMigrationTest {
    @TempDir Path directory;
    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("assistant.sqlite").toUri().toASCIIString());
    }
    private void seedVersionOne(boolean conflict) throws Exception {
        try (var connection = connect(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            sql.execute("INSERT INTO schema_version VALUES(1, 'synthetic-time')");
            sql.execute("CREATE TABLE preserved_data(value TEXT)");
            sql.execute("INSERT INTO preserved_data VALUES('keep-me')");
            if (conflict) sql.execute("CREATE TABLE conversation(existing TEXT)");
        }
    }
    @Test void upgradesVersionOneWithoutLosingExistingDataAndCanReopen() throws Exception {
        seedVersionOne(false);
        var config = TestProperties.valid(directory).storage();
        try (var store = SqliteStore.open(config)) {
            assertThat(store.schemaVersion()).isEqualTo(2);
            store.transaction(connection -> {
                try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT value FROM preserved_data")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("keep-me");
                }
                return null;
            });
        }
        try (var restored = SqliteStore.open(config)) { assertThat(restored.schemaVersion()).isEqualTo(2); }
    }
    @Test void migrationFailureRollsBackAllDdlAndDoesNotBumpVersion() throws Exception {
        seedVersionOne(true);
        assertThatThrownBy(() -> SqliteStore.open(TestProperties.valid(directory).storage()))
                .isInstanceOf(StorageProblem.class).hasMessageNotContaining("conversation").hasNoCause();
        try (var connection = connect(); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT MAX(version) FROM schema_version")) {
                assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(1);
            }
            try (var row = sql.executeQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('channel_state','inbound_event','turn')")) {
                assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isZero();
            }
            try (var row = sql.executeQuery("SELECT value FROM preserved_data")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("keep-me");
            }
        }
        assertThatThrownBy(() -> SqliteStore.open(TestProperties.valid(directory).storage())).isInstanceOf(StorageProblem.class);
    }
    @Test void enforcesBotMessageAndCurrentConversationUniquenessAndPrivacy() {
        try (var store = SqliteStore.open(TestProperties.valid(directory).storage())) {
            execute(store, "INSERT INTO conversation VALUES('c1','b1','u1',1,'now')");
            assertRejected(store, "INSERT INTO conversation VALUES('c2','b1','u1',1,'now')");
            execute(store, "INSERT INTO conversation VALUES('c3','b2','u1',1,'now')");
            execute(store, "INSERT INTO inbound_event(bot_id,message_id,sender_id,generation,content_kind,disposition,received_at) VALUES('b1','m1','u1',1,'TEXT','IGNORED','now')");
            assertRejected(store, "INSERT INTO inbound_event(bot_id,message_id,sender_id,generation,content_kind,disposition,received_at) VALUES('b1','m1','u1',1,'TEXT','IGNORED','now')");
            execute(store, "INSERT INTO inbound_event(bot_id,message_id,sender_id,generation,content_kind,disposition,received_at) VALUES('b2','m1','u1',1,'TEXT','IGNORED','now')");
            assertRejected(store, "INSERT INTO inbound_event(bot_id,message_id,sender_id,generation,text,content_kind,disposition,received_at) VALUES('b1','m2','u1',1,'do-not-store','TEXT','IGNORED','now')");
            assertRejected(store, "INSERT INTO inbound_event(bot_id,message_id,sender_id,generation,content_kind,disposition,received_at) VALUES('b1','m3','u1',1,'TEXT','ACCEPTED','now')");
            assertRejected(store, "INSERT INTO turn(event_sequence,bot_id,owner_id,conversation_id,kind,stage,model_status,delivery_status,updated_at) VALUES(1,'b1','u1','c3','CHAT','RECEIVED','NONE','NONE','now')");
        }
    }
    private static void execute(SqliteStore store, String text) {
        store.transaction(connection -> { try (var sql = connection.createStatement()) { sql.execute(text); } return null; });
    }
    private static void assertRejected(SqliteStore store, String text) {
        assertThatThrownBy(() -> execute(store, text)).isInstanceOf(StorageProblem.class).hasNoCause();
    }
}