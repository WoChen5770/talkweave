package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.runtime.PrivateStateFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Owns one local managed database. No network operations belong in its transactions. */
public final class ManagedStore implements AutoCloseable {
    public static final String DATABASE = "talkweave-admin.sqlite";
    public static final String MARKER = "managed-layout";
    private static final String LAYOUT = "talkweave-managed:1\n";
    private final Connection connection;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    private ManagedStore(Connection connection, FileChannel lockChannel, FileLock lock) {
        this.connection = connection; this.lockChannel = lockChannel; this.lock = lock;
    }

    public static ManagedStore open(Path input) {
        Path directory = input.toAbsolutePath().normalize();
        Connection connection = null;
        FileChannel channel = null;
        FileLock lock = null;
        try {
            // Crucially BEFORE mkdir/chmod/lock/JDBC writable open. Legacy data is never touched.
            boolean fresh = inspect(directory);
            Files.createDirectories(directory);
            PrivateStateFiles.restrict(directory, true);
            Path lockPath = directory.resolve("managed.lock");
            rejectLinks(lockPath);
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException failure) { throw new ManagedProblem(DIRECTORY_IN_USE); }
            if (lock == null) throw new ManagedProblem(DIRECTORY_IN_USE);
            PrivateStateFiles.restrict(lockPath, false);
            // Recheck an existing layout after acquiring ownership; schema was preflighted read-only.
            if (!fresh) inspect(directory);
            Path database = directory.resolve(DATABASE);
            for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) rejectLinks(Path.of(database + suffix));
            if (fresh) Files.createFile(database);
            PrivateStateFiles.restrict(database, false);
            connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri().toASCIIString());
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
            }
            if (fresh) {
                initialize(connection);
            }
            migrate(connection);
            if (fresh) {
                try (var statement = connection.createStatement()) { statement.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
                Path marker = directory.resolve(MARKER);
                Files.createFile(marker);
                PrivateStateFiles.restrict(marker, false);
                Files.writeString(marker, LAYOUT, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            }
            var store = new ManagedStore(connection, channel, lock);
            store.transaction(c -> {
                Sql.update(c, "UPDATE turn SET stage='INTERRUPTED' WHERE stage='PROCESSING'");
                Sql.update(c, "UPDATE turn SET stage='DELIVERY_UNKNOWN' WHERE stage='SENDING'");
                Sql.update(c, "UPDATE model_attempt SET outcome='UNKNOWN' WHERE outcome='STARTED'");
                Sql.update(c, "UPDATE binding_attempt SET phase='CANCELLED' WHERE phase IN ('REQUESTING_QR','QR_READY','SCANNED','NEED_PAIRING','VERIFYING_IDENTITY')");
                return null;
            });
            return store;
        } catch (ManagedProblem failure) {
            close(connection, lock, channel); throw failure;
        } catch (IOException failure) {
            close(connection, lock, channel); throw new ManagedProblem(INVALID_DIRECTORY);
        } catch (SQLException failure) {
            close(connection, lock, channel); throw new ManagedProblem(DATABASE_UNAVAILABLE);
        }
    }

    /** Inspect a private snapshot, including committed WAL, without changing the source directory. */
    private static boolean inspect(Path directory) throws IOException {
        rejectLinks(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return true;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new ManagedProblem(INVALID_DIRECTORY);
        for (String legacy : new String[]{"assistant.sqlite", "assistant.sqlite-wal", "assistant.sqlite-shm", "assistant.lock", "login"}) {
            if (Files.exists(directory.resolve(legacy), LinkOption.NOFOLLOW_LINKS)) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
        }
        Path marker = directory.resolve(MARKER);
        Path database = directory.resolve(DATABASE);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(directory)) {
                if (files.findAny().isPresent()) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
            }
            return true;
        }
        rejectLinks(marker); rejectLinks(database);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 128
                || !LAYOUT.equals(Files.readString(marker, StandardCharsets.UTF_8))
                || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
        Path snapshot = Files.createTempDirectory("talkweave-layout-check-");
        try {
            PrivateStateFiles.restrict(snapshot, true);
            Path copy = snapshot.resolve(DATABASE);
            for (String suffix : new String[]{"", "-wal"}) {
                Path source = directory.resolve(DATABASE + suffix);
                rejectLinks(source);
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
                    Files.copy(source, snapshot.resolve(DATABASE + suffix));
                }
            }
            try (var readOnly = DriverManager.getConnection("jdbc:sqlite:" + copy.toUri().toASCIIString() + "?mode=ro");
                 var statement = readOnly.createStatement()) {
                int version;
                try (var rows = statement.executeQuery("SELECT version FROM managed_schema")) {
                    if (!rows.next()) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
                    version = rows.getInt(1);
                    if ((version != 1 && version != 2) || rows.next()) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
                }
                try (var rows = statement.executeQuery("PRAGMA user_version")) {
                    if (!rows.next() || rows.getInt(1) != version) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
                }
                checkIntegrity(readOnly);
            } catch (SQLException failure) { throw new ManagedProblem(INCOMPATIBLE_LAYOUT); }
        } finally {
            // Only our fixed files within the newly allocated private snapshot are removed.
            for (String suffix : new String[]{"-shm", "-wal", "-journal", ""}) Files.deleteIfExists(snapshot.resolve(DATABASE + suffix));
            Files.deleteIfExists(snapshot);
        }
        return false;
    }

    private static void initialize(Connection connection) throws IOException, SQLException {
        connection.setAutoCommit(false);
        try (var input = ManagedStore.class.getResourceAsStream("/db/managed/V001__managed.sql")) {
            if (input == null) throw new IOException("Managed schema resource unavailable");
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) try (var statement = connection.createStatement()) { statement.execute(sql); }
            }
            connection.commit();
        } catch (IOException | SQLException failure) {
            connection.rollback(); throw failure;
        } finally { connection.setAutoCommit(true); }
    }

    /** SQLite's table-rebuild procedure, before runtime starts and while holding the directory lock.
     * Disabling enforcement is confined to the schema transaction; all rows are checked before commit.
     */
    static void migrate(Connection connection) throws IOException, SQLException {
        if (Sql.scalar(connection, "PRAGMA user_version") == 2) return;
        if (Sql.scalar(connection, "PRAGMA user_version") != 1) throw new SQLException("Unsupported managed schema");
        checkIntegrity(connection);
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=OFF"); }
        connection.setAutoCommit(false);
        try (var input = ManagedStore.class.getResourceAsStream("/db/managed/V002__binding_connections.sql")) {
            if (input == null) throw new IOException("Managed migration resource unavailable");
            // Preserve AUTOINCREMENT's high-water mark even if the highest event was previously removed.
            long sequence = Sql.scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='inbound_event'");
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) try (var statement = connection.createStatement()) { statement.execute(sql); }
            }
            Sql.update(connection, "UPDATE sqlite_sequence SET seq=max(seq,?) WHERE name='inbound_event'", sequence);
            Sql.update(connection, "INSERT INTO sqlite_sequence(name,seq) SELECT 'inbound_event',? WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name='inbound_event')", sequence);
            checkIntegrity(connection);
            connection.commit();
        } catch (IOException | SQLException | RuntimeException failure) {
            connection.rollback(); throw failure;
        } finally {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        }
        if (Sql.scalar(connection, "PRAGMA foreign_keys") != 1) throw new SQLException("Foreign keys not enabled");
    }

    private static void checkIntegrity(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw new SQLException("Managed foreign key check failed");
            }
            try (var rows = statement.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next())
                    throw new SQLException("Managed integrity check failed");
            }
        }
    }

    @FunctionalInterface interface Work<T> { T run(Connection connection) throws SQLException; }
    synchronized <T> T transaction(Work<T> work) {
        if (closed) throw new ManagedProblem(DATABASE_UNAVAILABLE);
        try {
            if (!connection.getAutoCommit()) throw new ManagedProblem(DATABASE_UNAVAILABLE);
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException | Error failure) {
                connection.rollback();
                if (failure instanceof SQLException sql) throw new ManagedProblem(sql.getErrorCode() == 19 ? CONFLICT : DATABASE_UNAVAILABLE);
                throw failure;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) { throw new ManagedProblem(DATABASE_UNAVAILABLE); }
    }

    private static void rejectLinks(Path path) throws IOException {
        Path part = path.getRoot();
        for (Path component : path) {
            part = part.resolve(component);
            if (Files.isSymbolicLink(part)) throw new IOException("Unsafe storage path");
        }
    }
    private static void close(Connection connection, FileLock lock, FileChannel channel) {
        try { if (connection != null) connection.close(); } catch (SQLException ignored) { }
        try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) { }
        try { if (channel != null) channel.close(); } catch (IOException ignored) { }
    }
    @Override public synchronized void close() {
        if (!closed) { closed = true; close(connection, lock, lockChannel); }
    }
}
