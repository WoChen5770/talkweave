package io.github.wochen5770.talkweave.managed.persistence;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.wochen5770.talkweave.managed.config.ExternalServices;
import java.sql.Connection;
import java.sql.SQLException;

/** The lock uses a dedicated physical connection, never a pooled or transparently reconnected one. */
public final class MysqlConnections {
    private MysqlConnections() { }

    public static Connection dedicated(ExternalServices.Mysql config) throws SQLException {
        return source(config).getConnection();
    }

    public static HikariDataSource pool(ExternalServices.Mysql config) throws SQLException {
        var pool = new HikariConfig();
        pool.setPoolName("talkweave-mysql");
        pool.setDataSource(source(config));
        pool.setMaximumPoolSize(config.poolSize());
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(config.acquireTimeout().toMillis());
        pool.setValidationTimeout(Math.min(1000, config.acquireTimeout().toMillis()));
        pool.setInitializationFailTimeout(-1);
        pool.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        pool.setConnectionInitSql("SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION'");
        return new HikariDataSource(pool);
    }

    private static MysqlDataSource source(ExternalServices.Mysql config) throws SQLException {
        // Connector/J's getLoginTimeout() always returns zero. Hikari uses it as the
        // shutdown wait for connection creation, so expose the configured finite wait.
        var source = new MysqlDataSource() {
            @Override public int getLoginTimeout() {
                return Math.toIntExact((config.connectTimeout().toMillis() + config.socketTimeout().toMillis() + 999) / 1000);
            }
        };
        source.setServerName(config.host());
        source.setPort(config.port());
        source.setDatabaseName(config.schema());
        source.setUser(config.username());
        source.setPassword(config.password());
        source.setSslMode(config.sslMode().name());
        source.setConnectTimeout(Math.toIntExact(config.connectTimeout().toMillis()));
        source.setSocketTimeout(Math.toIntExact(config.socketTimeout().toMillis()));
        source.setAutoReconnect(false);
        source.setAutoReconnectForPools(false);
        source.setAllowPublicKeyRetrieval(false);
        source.setAllowLoadLocalInfile(false);
        source.setAllowMultiQueries(false);
        source.setCharacterEncoding("UTF-8");
        source.setConnectionCollation("utf8mb4_0900_bin");
        source.setLogger("com.mysql.cj.log.NullLogger");
        return source;
    }
}
