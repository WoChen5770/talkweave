package io.github.personalassistant.persistence;

import io.github.personalassistant.runtime.AssistantProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import io.github.personalassistant.runtime.PrivateStateFiles;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import static io.github.personalassistant.persistence.StorageProblem.Reason.*;

/** Owns a process lock and a single SQLite connection. Network calls never belong here. */
public final class SqliteStore implements AutoCloseable {
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Connection connection;
    private boolean closed;
    private final Path directory;

    private SqliteStore(FileChannel channel, FileLock lock, Connection connection, Path directory) {
        this.directory = directory;
        this.lockChannel = channel;
        this.lock = lock;
        this.connection = connection;
    }

    public static SqliteStore open(AssistantProperties.Storage config) {
        Path directory = config.dataPath();
        FileChannel channel = null;
        FileLock lock = null;
        Connection connection = null;
        try {
            rejectSymlinkComponents(directory);
            Files.createDirectories(directory);
            restrict(directory, "rwx------");
            if (!Files.isDirectory(directory) || !Files.isWritable(directory)) throw new StorageProblem(INVALID_DIRECTORY);
            Path probe = Files.createTempFile(directory, ".write-check-", ".tmp");
            Files.delete(probe);
            Path lockFile = directory.resolve("assistant.lock");
            rejectSymlinkComponents(lockFile);
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { throw new StorageProblem(DIRECTORY_IN_USE); }
            if (lock == null) throw new StorageProblem(DIRECTORY_IN_USE);
            restrict(lockFile, "rw-------");
            Path database = directory.resolve("assistant.sqlite");
            for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) rejectSymlinkComponents(Path.of(database + suffix));
            if (!Files.exists(database)) Files.createFile(database);
            restrict(database, "rw-------");
            // URI form prevents '?' and '#' in filesystem names from becoming JDBC options.
            connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri().toASCIIString());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=" + config.busyTimeout().toMillis());
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
            }
            bootstrap(connection);
            return new SqliteStore(channel, lock, connection, directory);
        } catch (StorageProblem e) {
            closeQuietly(connection, lock, channel);
            throw e;
        } catch (IOException e) {
            closeQuietly(connection, lock, channel);
            throw new StorageProblem(INVALID_DIRECTORY);
        } catch (SQLException e) {
            closeQuietly(connection, lock, channel);
            throw new StorageProblem(DATABASE_UNAVAILABLE);
        }
    }

    private static void bootstrap(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            int version;
            try (var result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
                result.next();
                version = result.getInt(1);
            }
            if (version > 2) throw new StorageProblem(UNSUPPORTED_SCHEMA);
            if (version == 0) recordVersion(connection, 1);
            if (version < 2) {
                try (var input = SqliteStore.class.getResourceAsStream("/db/migration/V002__conversation_state.sql")) {
                    if (input == null) throw new StorageProblem(DATABASE_UNAVAILABLE);
                    String script = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    for (String sql : script.split(";")) if (!sql.isBlank()) statement.execute(sql);
                } catch (IOException e) { throw new StorageProblem(DATABASE_UNAVAILABLE); }
                recordVersion(connection, 2);
            }
            // Only opening a new locked store is a restart boundary; never run this on a live consumer.
            statement.executeUpdate("UPDATE turn SET stage='INTERRUPTED',model_status=CASE WHEN model_status='RUNNING' THEN 'INTERRUPTED' ELSE model_status END,diagnostic='RESTART_DURING_PROCESSING' WHERE stage='PROCESSING'");
            statement.executeUpdate("UPDATE turn SET stage='DELIVERY_UNKNOWN',delivery_status='UNKNOWN',diagnostic='RESTART_DURING_SEND' WHERE stage='SENDING'");
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void recordVersion(Connection connection, int version) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO schema_version(version, applied_at) VALUES (?, ?)")) {
            insert.setInt(1, version);
            insert.setString(2, Instant.now().toString());
            insert.executeUpdate();
        }
    }

    @FunctionalInterface
    interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    /** Package-private: only repositories may submit short, local SQL operations. */
    synchronized <T> T transaction(SqlWork<T> work) {
        if (closed) throw new StorageProblem(DATABASE_UNAVAILABLE);
        try {
            if (!connection.getAutoCommit()) throw new StorageProblem(DATABASE_UNAVAILABLE);
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException | Error failure) {
                connection.rollback();
                if (failure instanceof SQLException) throw new StorageProblem(DATABASE_UNAVAILABLE);
                throw failure;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) { throw new StorageProblem(DATABASE_UNAVAILABLE); }
    }

    long usableSpace() {
        try { return Files.getFileStore(directory).getUsableSpace(); }
        catch (IOException failure) { throw new StorageProblem(DATABASE_UNAVAILABLE); }
    }
    public synchronized int schemaVersion() {
        if (closed) throw new StorageProblem(DATABASE_UNAVAILABLE);
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
            result.next();
            return result.getInt(1);
        } catch (SQLException e) { throw new StorageProblem(DATABASE_UNAVAILABLE); }
    }

    private static void rejectSymlinkComponents(Path path) throws IOException {
        Path part = path.getRoot();
        for (Path name : path) {
            part = part.resolve(name);
            if (Files.isSymbolicLink(part)) throw new IOException("Symlink rejected");
        }
    }

    private static void restrict(Path path, String permissions) throws IOException {
        PrivateStateFiles.restrict(path, permissions.equals("rwx------"));
    }

    private static void closeQuietly(Connection connection, FileLock lock, FileChannel channel) {
        try { if (connection != null) connection.close(); } catch (SQLException ignored) { }
        try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) { }
        try { if (channel != null) channel.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void close() {
        if (!closed) {
            closed = true;
            closeQuietly(connection, lock, lockChannel);
        }
    }
}
