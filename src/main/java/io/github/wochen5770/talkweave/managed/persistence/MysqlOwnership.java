package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.managed.config.ExternalServices;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Single-process ownership. Any failed check permanently invalidates this object. */
public final class MysqlOwnership implements AutoCloseable {
    private final Connection connection;
    private final String name;
    private volatile boolean lost;
    // A cancelled virtual user worker must never close the shared ownership socket.
    private final ExecutorService checks = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mysql-ownership").factory());

    private MysqlOwnership(Connection connection, String name) { this.connection = connection; this.name = name; }

    public static MysqlOwnership acquire(ExternalServices.Mysql config) {
        Connection connection = null;
        try {
            connection = MysqlConnections.dedicated(config);
            MysqlLayout.inspect(connection);
            String name = "talkweave:" + digest(config.schema().toLowerCase(Locale.ROOT)).substring(0, 48);
            if (Sql.scalar(connection, "SELECT GET_LOCK(?,0)", name) != 1) throw new ManagedProblem(DIRECTORY_IN_USE);
            return new MysqlOwnership(connection, name);
        } catch (SQLException | RuntimeException failure) {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
            if (failure instanceof ManagedProblem problem) throw problem;
            throw new ManagedProblem(DATABASE_UNAVAILABLE);
        }
    }

    public synchronized void check() {
        if (lost) throw new ManagedProblem(DATABASE_UNAVAILABLE);
        try {
            checks.submit(() -> {
                try {
                    if (lost || Sql.scalar(connection, "SELECT IS_USED_LOCK(?) = CONNECTION_ID()", name) != 1) throw new SQLException();
                } catch (SQLException failure) {
                    lost = true;
                    try { connection.close(); } catch (SQLException ignored) { }
                    throw new ManagedProblem(DATABASE_UNAVAILABLE);
                }
            }).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ManagedProblem(DATABASE_UNAVAILABLE);
        } catch (ExecutionException | RejectedExecutionException failure) {
            throw new ManagedProblem(DATABASE_UNAVAILABLE);
        }
    }

    synchronized <T> T use(SqlWork<T> work) throws SQLException {
        check();
        try { return work.run(connection); }
        catch (SQLException failure) {
            // DDL/constraint failure isn't proof of transport loss; checking failure latches it.
            check(); throw failure;
        }
    }
    @FunctionalInterface interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    @Override public synchronized void close() {
        if (!lost) {
            lost = true;
            try { connection.close(); } catch (SQLException ignored) { }
        }
        checks.shutdown();
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
