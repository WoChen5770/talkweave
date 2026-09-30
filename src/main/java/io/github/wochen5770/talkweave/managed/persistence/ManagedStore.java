package io.github.wochen5770.talkweave.managed.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.github.wochen5770.talkweave.managed.config.ExternalServices;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** One MySQL installation and its exclusive runtime. Transactions contain JDBC work only. */
public final class ManagedStore implements AutoCloseable {
    private final MysqlOwnership owner;
    private final HikariDataSource pool;
    private final MysqlTransactions transactions;
    private final MysqlLayout.Opened layout;
    private final String cacheEpoch = UUID.randomUUID().toString();

    private ManagedStore(MysqlOwnership owner, HikariDataSource pool, MysqlLayout.Opened layout) {
        this.owner = owner; this.pool = pool; this.layout = layout;
        transactions = new MysqlTransactions(pool, owner, layout.epoch());
    }

    public static ManagedStore open(ExternalServices.Mysql config) {
        var owner = MysqlOwnership.acquire(config);
        try { return openOwned(config, owner); }
        catch (SQLException failure) { owner.close(); throw new ManagedProblem(ManagedProblem.Code.DATABASE_UNAVAILABLE); }
        catch (RuntimeException | Error failure) { owner.close(); throw failure; }
    }

    // Test fixtures may preflight their explicitly approved target while holding this same lock.
    static ManagedStore openOwned(ExternalServices.Mysql config, MysqlOwnership owner) throws SQLException {
        var layout = MysqlLayout.open(owner);
        var pool = MysqlConnections.pool(config);
        var store = new ManagedStore(owner, pool, layout);
        try {
            store.transaction(c -> {
                Sql.update(c, "UPDATE turn SET stage='INTERRUPTED' WHERE stage='PROCESSING' AND runtime_epoch<?", layout.epoch());
                Sql.update(c, "UPDATE turn SET stage='DELIVERY_UNKNOWN' WHERE stage='SENDING' AND runtime_epoch<?", layout.epoch());
                Sql.update(c, "UPDATE model_attempt SET outcome='UNKNOWN' WHERE outcome='STARTED' AND runtime_epoch<?", layout.epoch());
                Sql.update(c, "UPDATE binding_attempt SET phase='CANCELLED' WHERE phase IN ('REQUESTING_QR','QR_READY','SCANNED','NEED_PAIRING','VERIFYING_IDENTITY')");
                Sql.update(c, "DELETE FROM active_invitation");
                return null;
            });
            return store;
        } catch (RuntimeException | Error failure) { store.close(); throw failure; }
    }

    public long epoch() { return layout.epoch(); }
    public String installationId() { return layout.installationId(); }
    public String cacheEpoch() { return cacheEpoch; }
    public void checkOwnership() { owner.check(); }
    public boolean ownershipLost() { return owner.isLost(); }
    public void fence() { owner.fence(); }
    /** Aggregate pool acquisition time only; contains no scope, SQL or connection details. */
    public long poolAcquireNanos() { return transactions.acquireNanos(); }
    @FunctionalInterface interface Work<T> { T run(Connection connection) throws SQLException; }
    <T> T transaction(Work<T> work) { return transactions.execute(work::run); }

    @Override public void close() {
        // Fence dispatch before closing pooled connections. Never reconnect this owner.
        owner.close(); pool.close();
    }
}
