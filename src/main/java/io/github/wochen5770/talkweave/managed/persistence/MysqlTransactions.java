package io.github.wochen5770.talkweave.managed.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Short JDBC-only transactions. Callbacks MUST NOT dispatch models, WeChat, Redis or other external work. */
final class MysqlTransactions {
    @FunctionalInterface interface Work<T> { T run(Connection connection) throws SQLException; }
    private static final int DEADLOCK_RETRIES = 2;
    private final DataSource source;
    private final MysqlOwnership owner;
    private final long epoch;
    private final java.util.concurrent.atomic.LongAdder acquireNanos = new java.util.concurrent.atomic.LongAdder();
    long acquireNanos() { return acquireNanos.sum(); }
    private Connection acquire() throws SQLException {
        long start = System.nanoTime();
        try { return source.getConnection(); }
        finally { acquireNanos.add(System.nanoTime() - start); }
    }

    MysqlTransactions(DataSource source, MysqlOwnership owner, long epoch) {
        this.source = Objects.requireNonNull(source);
        this.owner = Objects.requireNonNull(owner);
        if (epoch <= 0) throw new IllegalArgumentException("Invalid runtime epoch");
        this.epoch = epoch;
    }

    <T> T execute(Work<T> work) {
        Objects.requireNonNull(work);
        for (int attempt = 0; ; attempt++) {
            checkActive();
            try (var c = acquire()) {
                if (!c.getAutoCommit()) throw new ManagedProblem(DATABASE_UNAVAILABLE);
                c.setAutoCommit(false);
                boolean resetSafe = false;
                boolean commitStarted = false;
                try {
                    verifyEpoch(c);
                    T result = work.run(c);
                    checkActive();
                    commitStarted = true;
                    c.commit();
                    resetSafe = true;
                    return result;
                } catch (SQLException failure) {
                    resetSafe = rollback(c);
                    if (!resetSafe) throw new ManagedProblem(DATABASE_UNAVAILABLE);
                    // Never retry an ambiguous COMMIT, transport loss, lock timeout, or external business stage.
                    if (!commitStarted && failure.getErrorCode() == 1213 && attempt < DEADLOCK_RETRIES) continue;
                    throw classify(failure);
                } catch (RuntimeException | Error failure) {
                    resetSafe = rollback(c);
                    if (!resetSafe && failure instanceof RuntimeException) throw new ManagedProblem(DATABASE_UNAVAILABLE);
                    throw failure;
                } finally {
                    // Changing auto-commit can itself commit: do it ONLY after confirmed commit/rollback.
                    if (resetSafe) c.setAutoCommit(true);
                }
            } catch (SQLException failure) { throw classify(failure); }
        }
    }

    private void checkActive() {
        if (Thread.currentThread().isInterrupted()) throw new ManagedProblem(DATABASE_UNAVAILABLE);
        owner.check();
    }

    private void verifyEpoch(Connection c) throws SQLException {
        // Lock order: runtime epoch (shared), capacity guard if needed, user, binding, conversation.
        // A replacement owner's epoch increment waits for already-open short transactions to finish.
        if (Sql.scalar(c, "SELECT epoch FROM runtime_owner WHERE slot=1 FOR SHARE") != epoch) {
            owner.close(); // Irreversible, even if the persisted value is subsequently changed back.
            throw new ManagedProblem(DATABASE_UNAVAILABLE);
        }
    }

    private boolean rollback(Connection c) {
        try { c.rollback(); return true; }
        catch (SQLException failure) {
            // Abort ONLY this borrowed physical connection; never return uncertain work to the pool.
            boolean aborted = false;
            try { c.abort(Runnable::run); aborted = true; } catch (SQLException ignored) { }
            // Virtual-thread cancellation closes its own socket. A confirmed abort is local to
            // that retired worker, not evidence that every other user's runtime lost ownership.
            if (!aborted || !Thread.currentThread().isInterrupted()) owner.close();
            return false;
        }
    }

    private static ManagedProblem classify(SQLException failure) {
        return new ManagedProblem("23000".equals(failure.getSQLState()) || failure.getErrorCode() == 3819
                ? CONFLICT : DATABASE_UNAVAILABLE);
    }
}
