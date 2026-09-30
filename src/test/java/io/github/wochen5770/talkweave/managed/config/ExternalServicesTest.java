package io.github.wochen5770.talkweave.managed.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ExternalServicesTest {
    static Map<String, Object> valid() {
        var values = new HashMap<String, Object>();
        values.put("managed.mysql.host", "127.0.0.1");
        values.put("managed.mysql.schema", "talkweave_test");
        values.put("managed.mysql.username", "fixture");
        values.put("managed.mysql.password", "mysql-secret-do-not-log");
        values.put("managed.redis.host", "localhost");
        values.put("managed.redis.password", "redis-secret-do-not-log");
        return values;
    }

    static ExternalServices read(Map<String, Object> values) {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("synthetic", values));
        return ExternalServices.read(environment);
    }

    @Test void defaultsAndExplicitPlaintextAreValidatedWithoutNetworking() {
        var config = read(valid());
        assertEquals(ExternalServices.SslMode.VERIFY_IDENTITY, config.mysql().sslMode());
        assertTrue(config.redis().tls());
        assertEquals("Asia/Shanghai", config.conversation().timeZone());
        assertEquals(Duration.ofMillis(250), config.historyCache().stageBudget());
        assertEquals(Duration.ofMinutes(60), config.historyCache().ttl());
        assertEquals(8, config.mysql().poolSize());
        var values = valid();
        values.put("managed.mysql.ssl-mode", "DISABLED");
        values.put("managed.redis.tls", false);
        config = read(values);
        assertEquals(ExternalServices.SslMode.DISABLED, config.mysql().sslMode());
        assertFalse(config.redis().tls());
    }

    @Test void redisMayBeOmittedOnlyWhenCacheIsDisabled() {
        var values = valid();
        values.keySet().removeIf(key -> key.startsWith("managed.redis."));
        assertThrows(IllegalArgumentException.class, () -> read(values));
        values.put("managed.history-cache.enabled", false);
        assertNull(read(values).redis());
    }

    @Test void connectionAndBindingErrorsNeverRetainValuesOrCauses() {
        var config = read(valid());
        String display = config + " " + config.mysql() + " " + config.redis();
        assertFalse(display.contains("secret"));
        var values = valid();
        values.put("managed.mysql.port", "secret-in-invalid-field");
        var failure = assertThrows(IllegalArgumentException.class, () -> read(values));
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("secret-in-invalid-field"));
    }

    @ParameterizedTest
    @CsvSource({"mysql.host", "mysql.schema", "mysql.username"})
    void missingRequiredValues(String field) {
        var values = valid(); values.remove("managed." + field);
        assertThrows(IllegalArgumentException.class, () -> read(values));
    }

    @ParameterizedTest
    @CsvSource({
            "mysql.schema, mysql", "mysql.schema, information_schema", "mysql.schema, injection?x=1",
            "mysql.host, user@host", "mysql.host, host/path", "mysql.host, host:3306",
            "mysql.port, 0", "mysql.port, 65536", "mysql.pool-size, 0", "mysql.pool-size, 65",
            "mysql.ssl-mode, PREFERRED", "mysql.connect-timeout, 0ms", "mysql.acquire-timeout, 249ms",
            "mysql.socket-timeout, 31s", "redis.database, -1", "redis.tls, invalid",
            "history-cache.key-prefix, '*'", "history-cache.key-prefix, other:project",
            "history-cache.ttl, 0s", "history-cache.ttl, 25h", "history-cache.max-entry-bytes, 1048577",
            "history-cache.command-timeout, 300ms", "history-cache.stage-budget, 99ms",
            "history-cache.max-concurrent, 0", "history-cache.request-queue-size, 257",
            "conversation.time-zone, Invalid/Zone", "materials-directory, /"
    })
    void invalidValuesFailClosed(String field, String value) {
        var values = valid(); values.put("managed." + field, value);
        var failure = assertThrows(IllegalArgumentException.class, () -> read(values));
        assertNull(failure.getCause());
    }
}
