package io.github.wochen5770.talkweave.managed.persistence;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

class ManagedStoreTest {
    @TempDir Path directory;

    @Test void startsFreshWithForeignKeysAndDefaultsAndReopensWithoutResetting() {
        try (var store = ManagedStore.open(directory)) {
            assertThat(store.<Integer>transaction(c -> {
                try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA foreign_keys")) { r.next(); return r.getInt(1); }
            })).isEqualTo(1);
            store.transaction(c -> { try (var s = c.createStatement()) { s.executeUpdate("UPDATE admin_setting SET idle_minutes=12"); } return null; });
            assertThatThrownBy(() -> ManagedStore.open(directory)).isInstanceOfSatisfying(ManagedProblem.class,
                    e -> assertThat(e.code()).isEqualTo(DIRECTORY_IN_USE));
        }
        try (var store = ManagedStore.open(directory)) {
            assertThat(store.<Integer>transaction(c -> {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT idle_minutes FROM admin_setting")) { r.next(); return r.getInt(1); }
            })).isEqualTo(12);
        }
    }

    @Test void legacyLayoutIsRejectedBeforeChangingPermissionsOrAnyFile() throws Exception {
        Files.writeString(directory.resolve("assistant.sqlite"), "fake-existing-chat-and-token");
        Files.createDirectory(directory.resolve("login"));
        assertUnchangedRejection();
    }

    @Test void unmarkedOrUnknownMarkerLayoutIsNotSilentlyInitialized() throws Exception {
        Files.writeString(directory.resolve(ManagedStore.MARKER), "talkweave-managed:999\n");
        Files.writeString(directory.resolve(ManagedStore.DATABASE), "fake-private-data");
        assertUnchangedRejection();
    }

    @Test void unsupportedDatabaseVersionInCommittedWalIsRejectedWithoutTouchingSource() throws Exception {
        try (var ignored = ManagedStore.open(directory)) { }
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(ManagedStore.DATABASE).toUri().toASCIIString());
             var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA wal_autocheckpoint=0");
            s.execute("PRAGMA user_version=99");
            assertThat(directory.resolve(ManagedStore.DATABASE + "-wal")).exists();
            assertUnchangedRejection();
        }
    }

    @Test void unknownSqlSchemaIsRejectedEvenWithMatchingMarker() throws Exception {
        try (var ignored = ManagedStore.open(directory)) { }
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(ManagedStore.DATABASE).toUri().toASCIIString());
             var s = c.createStatement()) {
            s.execute("DROP TABLE managed_schema");
        }
        assertUnchangedRejection();
    }

    @Test void incompleteInitializationAndForeignFilesAreNotDeleted() throws Exception {
        Files.writeString(directory.resolve("some-important-file"), "keep");
        assertUnchangedRejection();
    }

    @Test void transactionRollsBackAndDoesNotExposeSqlValues() {
        try (var store = ManagedStore.open(directory)) {
            assertThatThrownBy(() -> store.transaction(c -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate("UPDATE admin_setting SET idle_minutes=10");
                    s.executeUpdate("INSERT INTO app_user VALUES ('fake-secret-id','label',1,1,0)");
                    s.executeUpdate("INSERT INTO app_user VALUES ('fake-secret-id','label',1,1,0)");
                }
                return null;
            })).isInstanceOf(ManagedProblem.class).hasMessageNotContaining("fake-secret-id");
            assertThat(store.<Integer>transaction(c -> {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT idle_minutes FROM admin_setting")) { r.next(); return r.getInt(1); }
            })).isEqualTo(30);
        }
    }

    private void assertUnchangedRejection() throws Exception {
        var bytes = snapshot();
        Object permissions = permissions(directory);
        var filePermissions = new HashMap<String, Object>();
        for (String name : bytes.keySet()) filePermissions.put(name, permissions(directory.resolve(name)));
        assertThatThrownBy(() -> ManagedStore.open(directory)).isInstanceOfSatisfying(ManagedProblem.class,
                e -> assertThat(e.code()).isEqualTo(INCOMPATIBLE_LAYOUT));
        assertThat(permissions(directory)).isEqualTo(permissions);
        var after = snapshot();
        assertThat(after.keySet()).isEqualTo(bytes.keySet());
        for (String name : bytes.keySet()) {
            assertThat(after.get(name)).isEqualTo(bytes.get(name));
            assertThat(permissions(directory.resolve(name))).isEqualTo(filePermissions.get(name));
        }
    }

    private Map<String, byte[]> snapshot() throws Exception {
        var result = new TreeMap<String, byte[]>();
        try (var files = Files.walk(directory)) {
            for (var path : files.toList()) {
                if (path.equals(directory)) continue;
                result.put(directory.relativize(path).toString(), Files.isRegularFile(path) ? Files.readAllBytes(path) : new byte[0]);
            }
        }
        return result;
    }
    private Object permissions(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) return Files.getPosixFilePermissions(path);
        return Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class).getAcl();
    }
}