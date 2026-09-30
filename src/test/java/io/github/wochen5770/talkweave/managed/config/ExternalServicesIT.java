package io.github.wochen5770.talkweave.managed.config;

import io.github.wochen5770.talkweave.managed.cache.HistoryCacheFrame;
import io.github.wochen5770.talkweave.managed.persistence.MysqlConnections;
import io.lettuce.core.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in external-services profile only. No DDL on persistent tables or shared-service changes. */
class ExternalServicesIT {
    @Test void authorizedConnectionCapabilities() {
        String phase = "CONFIG";
        try {
            var target = ExternalIntegrationTarget.load();
            phase = "MYSQL_LOCK_AND_TRANSACTION";
            mysql(target);
            phase = "REDIS_ATOMIC_VERSION";
            redis(target);
        } catch (Throwable failure) {
            // Even assertion causes/driver messages may quote returned sensitive values. Never retain them.
            fail("NAS compatibility check failed: " + phase + " (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private static void mysql(ExternalIntegrationTarget target) throws Exception {
        var config = target.config();
        String lock = "talkweave:check:" + UUID.randomUUID();
        try (var first = MysqlConnections.dedicated(config.mysql());
             var second = MysqlConnections.dedicated(config.mysql());
             var pool = MysqlConnections.pool(config.mysql())) {
            target.verifyMysql(first);
            target.verifyMysql(second);
            assertEquals(1, scalar(first, "SELECT GET_LOCK(?,0)", lock));
            assertEquals(0, scalar(second, "SELECT GET_LOCK(?,0)", lock));
            first.abort(Runnable::run); // Abort only the connection created by this test, never KILL a server session.
            assertThrows(SQLException.class, () -> scalar(first, "SELECT 1"));
            assertEquals(1, scalar(second, "SELECT GET_LOCK(?,3)", lock));
            assertEquals(1, scalar(second, "SELECT RELEASE_LOCK(?)", lock));
            try (var connection = pool.getConnection(); var concurrent = pool.getConnection()) {
                assertNotEquals(scalar(connection, "SELECT CONNECTION_ID()"), scalar(concurrent, "SELECT CONNECTION_ID()"));
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                // Connection-private temporary table disappears on physical close. No persistent objects to clean up.
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TEMPORARY TABLE tw_compatibility_check (id BIGINT PRIMARY KEY, body TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL) ENGINE=InnoDB");
                }
                connection.setAutoCommit(false);
                try (var insert = connection.prepareStatement("INSERT INTO tw_compatibility_check VALUES (?,?)")) {
                    insert.setLong(1, Long.MAX_VALUE); insert.setString(2, "合成🙂e\u0301"); insert.executeUpdate();
                }
                connection.rollback();
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM tw_compatibility_check"));
                try (var insert = connection.prepareStatement("INSERT INTO tw_compatibility_check VALUES (?,?)")) {
                    insert.setLong(1, Long.MAX_VALUE); insert.setString(2, "合成🙂e\u0301"); insert.executeUpdate();
                }
                connection.commit();
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,body FROM tw_compatibility_check")) {
                    assertTrue(rows.next()); assertEquals(Long.MAX_VALUE, rows.getLong(1));
                    assertEquals("合成🙂e\u0301", rows.getString(2));
                }
                connection.setAutoCommit(true);
            }
            System.out.println("NAS MYSQL namedLock=PASS ownConnectionAbort=PASS poolIsolation=REPEATABLE_READ rollback=PASS unicodeAndInt64=PASS persistentObjectsWritten=0");
        }
    }

    private static void redis(ExternalIntegrationTarget target) {
        var config = target.config();
        var redis = config.redis();
        var uri = RedisURI.builder().withHost(redis.host()).withPort(redis.port()).withDatabase(redis.database())
                .withSsl(redis.tls()).withTimeout(Duration.ofSeconds(3));
        if (!redis.username().isBlank()) uri.withAuthentication(redis.username(), redis.password());
        else if (!redis.password().isEmpty()) uri.withPassword(redis.password().toCharArray());
        var client = RedisClient.create(uri.build());
        client.setOptions(ClientOptions.builder().autoReconnect(false).requestQueueSize(8)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(3)).build()).build());
        String key = target.redisKey("history-v1");
        try (var connection = client.connect()) {
            var commands = connection.sync();
            assertEquals("PONG", commands.ping());
            target.verifyRedis(commands.info("server"));
            // Random per-run key and 60s TTL: no DEL/SCAN/FLUSH or other project's keys.
            String old = HistoryCacheFrame.encode(9_007_199_254_740_992L, 2, "{\"synthetic\":true}", 4096);
            String next = HistoryCacheFrame.encode(9_007_199_254_740_993L, 2, "{\"synthetic\":true}", 4096);
            assertEquals(1L, (Long) commands.eval(HistoryCacheFrame.COMPARE_SET, ScriptOutputType.INTEGER, new String[]{key}, old, "60000", "4096"));
            assertEquals(1L, (Long) commands.eval(HistoryCacheFrame.COMPARE_SET, ScriptOutputType.INTEGER, new String[]{key}, next, "60000", "4096"));
            assertEquals(0L, (Long) commands.eval(HistoryCacheFrame.COMPARE_SET, ScriptOutputType.INTEGER, new String[]{key}, old, "60000", "4096"));
            String larger = HistoryCacheFrame.encode(9_007_199_254_740_993L, 3, "{\"synthetic\":true}", 4096);
            assertEquals(1L, (Long) commands.eval(HistoryCacheFrame.COMPARE_SET, ScriptOutputType.INTEGER, new String[]{key}, larger, "60000", "4096"));
            assertEquals(0L, (Long) commands.eval(HistoryCacheFrame.COMPARE_SET, ScriptOutputType.INTEGER, new String[]{key}, next, "60000", "4096"));
            assertEquals(larger, commands.get(key));
            String max = HistoryCacheFrame.encode(Long.MAX_VALUE, 3, "{\"synthetic\":true}", 4096);
            assertEquals(1L, (Long) commands.eval(HistoryCacheFrame.COMPARE_SET, ScriptOutputType.INTEGER, new String[]{key}, max, "60000", "4096"));
            assertEquals(0L, (Long) commands.eval(HistoryCacheFrame.COMPARE_SET, ScriptOutputType.INTEGER, new String[]{key}, larger, "60000", "4096"));
            assertTrue(commands.pttl(key) > 0 && commands.pttl(key) <= 60_000);
            System.out.println("NAS REDIS client=LETTUCE atomicInt64CAS=PASS sameRevisionCoverage=PASS ttl=60s syntheticKeys=1");
        } finally { client.shutdown(Duration.ZERO, Duration.ofSeconds(1)); }
    }

    private static long scalar(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(4);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
}
