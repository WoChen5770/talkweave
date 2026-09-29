package io.github.personalassistant.persistence;

import io.github.personalassistant.support.TestProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SqliteStoreTest {
    @TempDir Path temp;

    @Test void initializesOnceAndReleasesTheLockOnClose() {
        Path directory = temp.resolve("data with spaces");
        var config = TestProperties.valid(directory).storage();
        try (var store = SqliteStore.open(config)) {
            assertThat(store.schemaVersion()).isEqualTo(2);
            assertThatThrownBy(() -> SqliteStore.open(config)).isInstanceOf(StorageProblem.class)
                    .hasMessageContaining("DIRECTORY_IN_USE");
        }
        try (var restored = SqliteStore.open(config)) { assertThat(restored.schemaVersion()).isEqualTo(2); }
        assertThat(directory.resolve("assistant.sqlite")).exists();
    }

    @Test void rejectsAFileInsteadOfDataDirectory() throws Exception {
        Path file = temp.resolve("not-a-directory");
        Files.writeString(file, "original");
        assertThatThrownBy(() -> SqliteStore.open(TestProperties.valid(file).storage()))
                .isInstanceOf(StorageProblem.class).hasMessageContaining("INVALID_DIRECTORY");
        assertThat(Files.readString(file)).isEqualTo("original");
    }

    @Test void refusesCorruptDatabaseWithoutReplacingItAndReleasesLock() throws Exception {
        Path database = temp.resolve("assistant.sqlite");
        Files.writeString(database, "not a database - preserve me");
        var config = TestProperties.valid(temp).storage();
        assertThatThrownBy(() -> SqliteStore.open(config)).hasMessageContaining("DATABASE_UNAVAILABLE");
        assertThat(Files.readString(database)).isEqualTo("not a database - preserve me");
        assertThatThrownBy(() -> SqliteStore.open(config)).hasMessageContaining("DATABASE_UNAVAILABLE");
    }

    @Test void refusesNewerSchemaAndKeepsItsVersion() throws Exception {
        var config = TestProperties.valid(temp).storage();
        try (var ignored = SqliteStore.open(config)) { }
        String url = "jdbc:sqlite:" + temp.resolve("assistant.sqlite").toUri().toASCIIString();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO schema_version(version, applied_at) VALUES (3, 'synthetic')");
        }
        assertThatThrownBy(() -> SqliteStore.open(config)).hasMessageContaining("UNSUPPORTED_SCHEMA");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getInt(1)).isEqualTo(3);
        }
    }
}
