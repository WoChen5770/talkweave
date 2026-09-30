package io.github.wochen5770.talkweave.managed.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/** Validated new-runtime configuration. Binding errors must never retain secret source values. */
public record ExternalServices(Mysql mysql, Redis redis, @DefaultValue HistoryCache historyCache,
                               @DefaultValue Conversation conversation,
                               @DefaultValue("./materials") String materialsDirectory) {
    public ExternalServices {
        require(mysql != null, "mysql");
        require(historyCache != null, "history-cache");
        require(!historyCache.enabled() || redis != null, "redis");
        require(conversation != null, "conversation");
        require(materialsDirectory != null && !materialsDirectory.isBlank(), "materials-directory");
        try {
            require(Path.of(materialsDirectory).toAbsolutePath().normalize().getParent() != null, "materials-directory");
        } catch (RuntimeException invalid) { throw invalid("materials-directory"); }
    }

    public static ExternalServices read(Environment environment) {
        try {
            return Binder.get(environment).bind("managed", Bindable.of(ExternalServices.class))
                    .orElseThrow(() -> invalid("managed"));
        } catch (RuntimeException invalid) {
            // BindException/YAML conversion errors can retain password values. Do not chain them.
            throw invalid("managed external services (required fields, types or bounds)");
        }
    }

    @Override public String toString() { return "ExternalServices[connection details REDACTED]"; }
    public Path materialsPath() { return Path.of(materialsDirectory).toAbsolutePath().normalize(); }

    public enum SslMode { DISABLED, REQUIRED, VERIFY_CA, VERIFY_IDENTITY }

    public record Mysql(String host, @DefaultValue("3306") int port, String schema,
                        String username, @DefaultValue("") String password,
                        @DefaultValue("VERIFY_IDENTITY") SslMode sslMode,
                        @DefaultValue("8") int poolSize,
                        @DefaultValue("3s") Duration connectTimeout,
                        @DefaultValue("3s") Duration socketTimeout,
                        @DefaultValue("1s") Duration acquireTimeout) {
        public Mysql {
            endpoint(host, port, "mysql");
            require(schema != null && schema.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}")
                    && !Set.of("mysql", "sys", "information_schema", "performance_schema")
                    .contains(schema.toLowerCase(Locale.ROOT)), "mysql.schema");
            require(username != null && !username.isBlank() && username.length() <= 128, "mysql.username");
            require(password != null && password.length() <= 4096, "mysql.password");
            require(sslMode != null, "mysql.ssl-mode");
            require(poolSize >= 1 && poolSize <= 64, "mysql.pool-size");
            millis(connectTimeout, 100, 30_000, "mysql.connect-timeout");
            millis(socketTimeout, 100, 30_000, "mysql.socket-timeout");
            millis(acquireTimeout, 250, 5_000, "mysql.acquire-timeout");
        }
        @Override public String toString() { return "Mysql[REDACTED]"; }
    }

    public record Redis(String host, @DefaultValue("6379") int port, @DefaultValue("0") int database,
                        @DefaultValue("") String username, @DefaultValue("") String password,
                        @DefaultValue("true") boolean tls) {
        public Redis {
            endpoint(host, port, "redis");
            require(database >= 0, "redis.database");
            require(username != null && username.length() <= 128, "redis.username");
            require(password != null && password.length() <= 4096, "redis.password");
        }
        @Override public String toString() { return "Redis[REDACTED]"; }
    }

    public record HistoryCache(@DefaultValue("true") boolean enabled,
                               @DefaultValue("talkweave") String keyPrefix,
                               @DefaultValue("60m") Duration ttl,
                               @DefaultValue("262144") int maxEntryBytes,
                               @DefaultValue("100ms") Duration commandTimeout,
                               @DefaultValue("250ms") Duration stageBudget,
                               @DefaultValue("8") int maxConcurrent,
                               @DefaultValue("32") int requestQueueSize) {
        public HistoryCache {
            require(keyPrefix != null && keyPrefix.matches("[A-Za-z0-9_-]{1,64}"), "history-cache.key-prefix");
            millis(ttl, 1_000, 86_400_000, "history-cache.ttl");
            require(maxEntryBytes >= 1024 && maxEntryBytes <= 1_048_576, "history-cache.max-entry-bytes");
            millis(commandTimeout, 10, 1_000, "history-cache.command-timeout");
            millis(stageBudget, 10, 2_000, "history-cache.stage-budget");
            require(commandTimeout.compareTo(stageBudget) <= 0, "history-cache.stage-budget");
            require(maxConcurrent >= 1 && maxConcurrent <= 64, "history-cache.max-concurrent");
            require(requestQueueSize >= 1 && requestQueueSize <= 256, "history-cache.request-queue-size");
        }
    }

    public record Conversation(@DefaultValue("Asia/Shanghai") String timeZone) {
        public Conversation {
            try { ZoneId.of(timeZone); }
            catch (RuntimeException invalid) { throw invalid("conversation.time-zone"); }
        }
    }

    private static void endpoint(String host, int port, String name) {
        require(host != null && host.length() <= 253
                && (host.matches("[A-Za-z0-9][A-Za-z0-9.-]*")
                    || (host.indexOf(':') >= 0 && host.matches("[A-Fa-f0-9:]+"))), name + ".host");
        require(port > 0 && port <= 65535, name + ".port");
    }

    private static void millis(Duration value, long min, long max, String name) {
        require(value != null && value.compareTo(Duration.ofMillis(min)) >= 0
                && value.compareTo(Duration.ofMillis(max)) <= 0 && value.getNano() % 1_000_000 == 0, name);
    }

    private static void require(boolean valid, String field) { if (!valid) throw invalid(field); }
    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid external service configuration: " + field);
    }
}
