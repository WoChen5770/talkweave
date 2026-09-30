package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.managed.config.ExternalIntegrationTarget;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Own connections and rolled-back project fixtures only; no external business dispatch or shared-service faults. */
class ExternalTransactionsIT {
    @Test void parallelPooledTransactionsRollbackAndPermanentlyFenceOnOwnershipLoss() {
        String phase = "CONFIG";
        try {
            var target = ExternalIntegrationTarget.load();
            target.requireSchemaInitialization(); target.requireSchemaUpgrade();
            var config = target.config();
            if (config.mysql().poolSize() < 2) throw new IllegalArgumentException("Parallel integration requires a pool of at least two");
            try (var owner = MysqlOwnership.acquire(config.mysql())) {
                phase = "PREFLIGHT";
                try (var c = MysqlConnections.dedicated(config.mysql())) {
                    target.verifyMysql(c); target.requireNoBusinessData(c);
                }
                var opened = MysqlLayout.open(owner);
                try (var pool = MysqlConnections.pool(config.mysql())) {
                    var transactions = new MysqlTransactions(pool, owner, opened.epoch());
                    String user = UUID.randomUUID().toString();
                    phase = "JDBC_GENERATED_INT64_KEY";
                    try (var c = pool.getConnection(); var statement = c.createStatement()) {
                        statement.execute("CREATE TEMPORARY TABLE tw_generated_key_fixture (sequence BIGINT PRIMARY KEY AUTO_INCREMENT, body TEXT) "
                                + "ENGINE=InnoDB AUTO_INCREMENT=9007199254740993");
                        c.setAutoCommit(false);
                        try {
                            assertEquals(9_007_199_254_740_993L,Sql.insertId(c,"INSERT INTO tw_generated_key_fixture(body) VALUES (?)","synthetic"));
                        } finally { c.rollback(); c.setAutoCommit(true); }
                    }
                    phase = "ROLLBACK";
                    assertEquals(ManagedProblem.Code.BACKLOG, assertThrows(ManagedProblem.class, () -> transactions.execute(c -> {
                        Sql.update(c,"INSERT INTO app_user(id,label,created_at) VALUES (?,?,0)",user,target.fixtureLabel());
                        throw new ManagedProblem(ManagedProblem.Code.BACKLOG);
                    })).code());
                    long remaining = transactions.execute(c -> Sql.scalar(c,"SELECT COUNT(*) FROM app_user WHERE id=?",user));
                    assertEquals(0, remaining);
                    phase = "PARALLEL_SHORT_TRANSACTIONS";
                    try (var workers = Executors.newFixedThreadPool(2)) {
                        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
                        Callable<Long> read = () -> transactions.execute(c -> {
                            long connection = Sql.scalar(c,"SELECT CONNECTION_ID()"); entered.countDown();
                            try { if (!release.await(5,TimeUnit.SECONDS)) throw new SQLException("Synthetic barrier expired"); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new SQLException("Synthetic barrier interrupted"); }
                            return connection;
                        });
                        var first = workers.submit(read); var second = workers.submit(read);
                        boolean both;
                        try { both = entered.await(5,TimeUnit.SECONDS); } finally { release.countDown(); }
                        assertTrue(both, "Transactions must not be globally synchronized");
                        assertNotEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
                    }
                    phase = "OWNERSHIP_LOSS_BEFORE_COMMIT";
                    assertThrows(ManagedProblem.class, () -> transactions.execute(c -> {
                        Sql.update(c,"INSERT INTO app_user(id,label,created_at) VALUES (?,?,0)",user,target.fixtureLabel());
                        // Abort only the non-pooled session created and owned by this test.
                        owner.use(lockConnection -> { lockConnection.abort(Runnable::run); return null; });
                        return null;
                    }));
                    var ran = new AtomicBoolean();
                    assertThrows(ManagedProblem.class, () -> transactions.execute(c -> { ran.set(true); return null; }));
                    assertFalse(ran.get());
                    try (var check = pool.getConnection()) {
                        assertEquals(0,Sql.scalar(check,"SELECT COUNT(*) FROM app_user WHERE id=?",user));
                    }
                    // A distinct owner can acquire after closure; the old object's permission stays lost.
                    phase = "REPLACEMENT_OWNER";
                    try (var next = MysqlOwnership.acquire(config.mysql())) {
                        assertEquals(opened.epoch()+1,MysqlLayout.open(next).epoch());
                        assertThrows(ManagedProblem.class, () -> transactions.execute(c -> { ran.set(true); return null; }));
                        assertFalse(ran.get());
                    }
                }
            }
            System.out.println("MYSQL TRANSACTIONS parallelConnections=PASS rollback=PASS ownLockAbort=PASS oldOwnerPermanentlyFenced=PASS fixtureRows=ROLLED_BACK");
        } catch (Throwable failure) {
            String code = failure instanceof SQLException sql ? "SQL_"+sql.getErrorCode() : failure.getClass().getSimpleName();
            fail("External transaction check failed: "+phase+" ("+code+")");
        }
    }
}
