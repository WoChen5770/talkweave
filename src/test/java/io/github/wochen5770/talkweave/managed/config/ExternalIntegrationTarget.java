package io.github.wochen5770.talkweave.managed.config;

import java.nio.file.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Test-only, explicit target approval. Never consult Spring/OS production property sources. */
public final class ExternalIntegrationTarget {
    private final ExternalServices config;
    private final String mysqlVersion;
    private final String redisVersion;
    private final String runId = UUID.randomUUID().toString();
    private final boolean schemaInitialization;
    private final boolean schemaUpgrade;

    private ExternalIntegrationTarget(ExternalServices config, Properties approvals) {
        if (!required(approvals, "mysql-schema").equals(config.mysql().schema())
                || !required(approvals, "redis-prefix").equals(config.historyCache().keyPrefix())
                || config.redis() == null) throw invalid();
        this.mysqlVersion = version(required(approvals, "mysql-version"));
        this.redisVersion = version(required(approvals, "redis-version"));
        this.schemaInitialization = "true".equals(approvals.getProperty("talkweave.it.allow-schema-initialization"));
        this.schemaUpgrade = "true".equals(approvals.getProperty("talkweave.it.allow-schema-upgrade"));
        this.config = config;
    }

    public static ExternalIntegrationTarget load() { return load(System.getProperties()); }

    static ExternalIntegrationTarget load(Properties approvals) {
        try {
            // Fail before touching a config file or opening a socket when not explicitly enabled.
            if (!"true".equals(required(approvals, "enabled"))) throw invalid();
            Path path = Path.of(required(approvals, "config")).toAbsolutePath().normalize();
            for (Path part = path; part != null; part = part.getParent()) {
                if (Files.isSymbolicLink(part)) throw invalid();
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536) throw invalid();
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setCodePointLimit(65536);
            var properties = new HashMap<String, Object>();
            try (var reader = Files.newBufferedReader(path)) {
                flatten("", new Yaml(new SafeConstructor(options)).load(reader), properties);
            }
            var environment = new MockEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("explicit-integration-file", properties));
            return new ExternalIntegrationTarget(ExternalServices.read(environment), approvals);
        } catch (Exception failure) {
            // YAML/driver errors and assertion values can contain secrets; retain neither causes nor source values.
            throw invalid();
        }
    }

    static ExternalIntegrationTarget approved(ExternalServices config, Properties approvals) {
        return new ExternalIntegrationTarget(config, approvals);
    }

    public ExternalServices config() { return config; }
    public String fixtureLabel() { return "tw-it-" + runId; }
    public String redisKey(String purpose) {
        if (purpose == null || !purpose.matches("[a-z0-9-]{1,40}")) throw invalid();
        return config.historyCache().keyPrefix() + ":it:" + runId + ":" + purpose;
    }

    /** Read-only checks precede any test writes. Do not output target names or connection details. */
    public void verifyMysql(Connection connection) throws SQLException {
        if (!config.mysql().schema().equals(connection.getCatalog())
                || !mysqlVersion.equals(connection.getMetaData().getDatabaseProductVersion())
                || !connection.getMetaData().getDriverVersion().contains("9.6.0")) throw invalid();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT DATABASE()")) {
            if (!rows.next() || !config.mysql().schema().equals(rows.getString(1))) throw invalid();
        }
    }

    public void verifyRedis(String info) {
        String found = info.lines().filter(line -> line.startsWith("redis_version:"))
                .map(line -> line.substring("redis_version:".length())).findFirst().orElse("");
        if (!redisVersion.equals(found)) throw invalid();
    }

    public void requireSchemaInitialization() { if (!schemaInitialization) throw invalid(); }
    public void requireSchemaUpgrade() { if (!schemaUpgrade) throw invalid(); }

    /** Only after acquiring this schema's application lock, BEFORE epoch changes or fixtures. */
    public void requireNoBusinessData(Connection connection) throws SQLException {
        Set<String> present = new HashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            while (rows.next()) present.add(rows.getString(1));
        }
        for (String table : List.of("administrator", "app_user", "binding", "binding_connection", "active_binding",
                "channel_session", "binding_attempt", "active_invitation", "conversation", "active_conversation",
                "inbound_event", "turn", "model_configuration", "model_attempt", "model_usage", "audit_event")) {
            if (!present.contains(table)) continue;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT 1 FROM `" + table + "` LIMIT 1")) {
                if (rows.next()) throw new IllegalArgumentException("Integration schema contains business data; no reset permitted");
            }
        }
        if (present.contains("admin_setting")) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT 1 FROM admin_setting WHERE revision<>1 OR idle_minutes<>30 LIMIT 1")) {
                if (rows.next()) throw invalid();
            }
        }
    }

    private static void flatten(String prefix, Object value, Map<String, Object> properties) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid();
                flatten(prefix.isEmpty() ? key : prefix + "." + key, entry.getValue(), properties);
            }
        } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            properties.put(prefix, value);
        } else if (value != null) throw invalid();
    }

    private static String required(Properties approvals, String key) {
        String value = approvals.getProperty("talkweave.it." + key);
        if (value == null || value.isBlank() || value.startsWith("${")) throw invalid();
        return value;
    }
    private static String version(String value) {
        if (!value.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?")) throw invalid();
        return value;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("External integration requires explicit enablement, config, matching approved schema/prefix and exact versions");
    }
    @Override public String toString() { return "ExternalIntegrationTarget[REDACTED]"; }
}
