import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.net.ssl.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Explicit read-only operator check; outside all production/test source roots. */
public final class ExternalServicesCheck {
    private static final int TIMEOUT = 3000;
    private static final int MAX_REPLY = 64 * 1024;
    private static final Set<String> SSL_MODES = Set.of("DISABLED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY");

    public static void main(String[] args) {
        if (args.length != 2 || !Set.of("--validate-only", "--allow-read-only-network").contains(args[0])) {
            System.err.println("Usage: ExternalServicesCheck --validate-only|--allow-read-only-network <local-yaml>");
            System.exit(2);
        }
        Map<?, ?> mysql, redis;
        try {
            Path path = Path.of(args[1]);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_REPLY) throw new IllegalArgumentException();
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setCodePointLimit(MAX_REPLY);
            Map<?, ?> root;
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                root = map(new Yaml(new SafeConstructor(options)).load(reader));
            }
            var managed = map(root.get("managed"));
            mysql = map(managed.get("mysql")); redis = map(managed.get("redis"));
            endpoint(mysql); endpoint(redis);
            String schema = text(mysql, "schema", true);
            if (!schema.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}")
                    || Set.of("mysql", "sys", "information_schema", "performance_schema").contains(schema.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException();
            text(mysql, "username", true); text(mysql, "password", false);
            if (!SSL_MODES.contains(text(mysql, "ssl-mode", true))) throw new IllegalArgumentException();
            text(redis, "username", false); text(redis, "password", false);
            number(redis, "database", 0, Integer.MAX_VALUE);
            if (!(redis.get("tls") instanceof Boolean)) throw new IllegalArgumentException();
            System.out.println("CONFIG connectionFields=VALID runtimeSettings=NOT_VALIDATED secrets=REDACTED");
            System.out.println("MYSQL configuredTls=" + text(mysql, "ssl-mode", true));
            System.out.println("REDIS configuredTls=" + redis.get("tls"));
        } catch (Exception invalid) {
            // YAML exceptions can quote a secret-bearing source line. Never print them.
            System.err.println("CONFIG status=INVALID check=syntax_types_required_fields_host_port_schema_tls secrets=REDACTED");
            System.exit(2); return;
        }
        if (args[0].equals("--validate-only")) return;
        boolean ok = mysql(mysql);
        ok = redis(redis) && ok;
        if (!ok) System.exit(1);
    }

    private static boolean mysql(Map<?, ?> config) {
        String phase = "CONNECT";
        var properties = new Properties();
        properties.setProperty("user", text(config, "username", true));
        properties.setProperty("password", text(config, "password", false));
        properties.setProperty("sslMode", text(config, "ssl-mode", true));
        properties.setProperty("connectTimeout", Integer.toString(TIMEOUT));
        properties.setProperty("socketTimeout", Integer.toString(TIMEOUT));
        properties.setProperty("autoReconnect", "false");
        properties.setProperty("allowPublicKeyRetrieval", "false");
        properties.setProperty("allowLoadLocalInfile", "false");
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("logger", "com.mysql.cj.log.NullLogger");
        String schema = text(config, "schema", true);
        String host = text(config, "host", true);
        String url = "jdbc:mysql://" + (host.contains(":") ? "[" + host + "]" : host)
                + ":" + number(config, "port", 1, 65535) + "/" + schema;
        try (var connection = DriverManager.getConnection(url, properties)) {
            connection.setReadOnly(true);
            phase = "METADATA";
            System.out.println("MYSQL status=CONNECTED driver=" + safeVersion(connection.getMetaData().getDriverVersion()));
            try (var statement = connection.createStatement()) {
                // A single short read per query; no locks, DDL, DML or business-row reads.
                statement.setQueryTimeout(3);
                try (var rows = statement.executeQuery("SELECT VERSION(), @@transaction_isolation")) {
                    rows.next();
                    System.out.println("MYSQL serverVersion=" + safeVersion(rows.getString(1))
                            + " isolation=" + safeWord(rows.getString(2)));
                }
                try (var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                    boolean encrypted = rows.next() && !rows.getString(2).isBlank();
                    System.out.println("MYSQL transportEncrypted=" + encrypted);
                }
                try (var rows = statement.executeQuery("SHOW GRANTS")) {
                    boolean globalPrivileges = false, schemaCreate = false, schemaSelect = false;
                    while (rows.next()) {
                        String grant = rows.getString(1).toUpperCase(Locale.ROOT);
                        String target = "ON `" + schema.toUpperCase(Locale.ROOT) + "`.*";
                        boolean global = grant.contains("ON *.*") && !grant.startsWith("GRANT USAGE ");
                        globalPrivileges |= global;
                        if (grant.contains(target) || global) {
                            schemaCreate |= grant.contains("ALL PRIVILEGES") || grant.matches(".*\\bCREATE\\b.*");
                            schemaSelect |= grant.contains("ALL PRIVILEGES") || grant.matches(".*\\bSELECT\\b.*");
                        }
                    }
                    System.out.println("MYSQL visibleDirectGrants globalPrivileges=" + globalPrivileges
                            + " schemaCreate=" + schemaCreate + " schemaSelect=" + schemaSelect
                            + " rolePrivilegesNotExpanded=true");
                }
            }
            long tables = count(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?", schema);
            long routines = count(connection, "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=?", schema);
            long triggers = count(connection, "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=?", schema);
            long events = count(connection, "SELECT COUNT(*) FROM information_schema.events WHERE event_schema=?", schema);
            System.out.println("MYSQL visibleObjects tablesAndViews=" + tables + " routines=" + routines
                    + " triggers=" + triggers + " events=" + events + " dataRowsRead=0");
            return true;
        } catch (Exception failure) {
            System.err.println("MYSQL status=FAILED phase=" + phase + " category=" + category(failure));
            System.err.println("MYSQL tlsDetail=" + tlsDetail(failure));
            if (failure instanceof SQLException sql)
                System.err.println("MYSQL sqlState=" + safeWord(sql.getSQLState()) + " vendorCode=" + sql.getErrorCode());
            return false;
        } finally { properties.clear(); }
    }

    private static long count(Connection connection, String sql, String schema) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(3); statement.setString(1, schema);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private static boolean redis(Map<?, ?> config) {
        String phase = "CONNECT";
        try (var raw = new Socket()) {
            raw.connect(new InetSocketAddress(text(config, "host", true), number(config, "port", 1, 65535)), TIMEOUT);
            raw.setSoTimeout(TIMEOUT);
            System.out.println("REDIS tcpConnected=true");
            Socket socket = raw;
            if ((Boolean) config.get("tls")) {
                phase = "TLS_HANDSHAKE";
                var tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(
                        raw, text(config, "host", true), number(config, "port", 1, 65535), true);
                tls.setSoTimeout(TIMEOUT);
                var parameters = tls.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters); tls.startHandshake(); socket = tls;
            }
            try (var connection = socket;
                 var input = new DataInputStream(new BufferedInputStream(connection.getInputStream()));
                 var output = connection.getOutputStream()) {
                phase = "AUTH";
                String password = text(config, "password", false), username = text(config, "username", false);
                if (!password.isEmpty()) {
                    if (username.isEmpty()) command(input, output, "AUTH", password);
                    else command(input, output, "AUTH", username, password);
                }
                phase = "SELECT";
                command(input, output, "SELECT", Integer.toString(number(config, "database", 0, Integer.MAX_VALUE)));
                phase = "PING";
                if (!command(input, output, "PING").equals("PONG")) throw new IOException();
                System.out.println("REDIS status=CONNECTED ping=PONG transportEncrypted=" + config.get("tls"));
                phase = "INFO_SERVER";
                String info = command(input, output, "INFO", "server");
                String version = info.lines().filter(line -> line.startsWith("redis_version:"))
                        .map(line -> line.substring("redis_version:".length())).findFirst().orElse("UNKNOWN");
                System.out.println("REDIS serverVersion=" + safeVersion(version) + " keysRead=0 keysWritten=0");
                return true;
            }
        } catch (Exception failure) {
            System.err.println("REDIS status=FAILED phase=" + phase + " category=" + category(failure));
            return false;
        }
    }

    private static String command(DataInputStream input, OutputStream output, String... parts) throws IOException {
        // Only these session/diagnostic commands can be emitted, even if this helper is later reused.
        if (!Set.of("AUTH", "SELECT", "PING", "INFO").contains(parts[0])) throw new IOException();
        var request = new ByteArrayOutputStream();
        request.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            request.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            request.write(bytes); request.write('\r'); request.write('\n');
        }
        output.write(request.toByteArray()); output.flush();
        int type = input.read(); String line = line(input);
        if (type == '-') {
            String code = line.split(" ", 2)[0];
            throw new RedisFailure(Set.of("WRONGPASS", "NOAUTH", "NOPERM", "ERR").contains(code) ? code : "SERVER_ERROR");
        }
        if (type == '+') return line;
        if (type != '$') throw new IOException();
        int size;
        try { size = Integer.parseInt(line); } catch (NumberFormatException invalid) { throw new IOException(); }
        if (size < 0 || size > MAX_REPLY) throw new IOException();
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size || input.read() != '\r' || input.read() != '\n') throw new IOException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String line(DataInputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (bytes.size() < MAX_REPLY) {
            int value = input.read();
            if (value < 0) throw new EOFException();
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException();
                return bytes.toString(StandardCharsets.UTF_8);
            }
            bytes.write(value);
        }
        throw new IOException();
    }

    private static final class RedisFailure extends IOException {
        final String code;
        RedisFailure(String code) { this.code = code; }
    }
    private static String category(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SSLException) return "TLS";
            if (t instanceof SocketTimeoutException) return "TIMEOUT";
            if (t instanceof UnknownHostException) return "DNS";
            if (t instanceof ConnectException) return "CONNECT";
            if (t instanceof RedisFailure r) return r.code;
        }
        return failure instanceof SQLException ? "SQL" : "IO_OR_PROTOCOL";
    }
    private static String tlsDetail(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.security.cert.CertPathValidatorException
                    || t instanceof java.security.cert.CertPathBuilderException) return "CERTIFICATE_TRUST";
            // Match fixed failure categories only; never expose provider text or configured names.
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("pkix") || message.contains("unable to find valid certification path")) return "CERTIFICATE_TRUST";
            if (message.contains("subject alternative") || message.contains("identity verification failed")
                    || message.contains("no name matching")) return "CERTIFICATE_HOSTNAME";
            if (message.contains("ssl") && message.contains("required") && message.contains("not provided")) return "SERVER_TLS_UNAVAILABLE";
        }
        return "UNKNOWN";
    }
    private static String safeVersion(String value) {
        if (value == null) return "UNKNOWN";
        var matcher = java.util.regex.Pattern.compile("(?<![0-9])[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").matcher(value);
        return matcher.find() ? matcher.group() : "UNKNOWN";
    }
    private static String safeWord(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,40}") ? value : "UNKNOWN"; }
    private static Map<?, ?> map(Object value) { if (value instanceof Map<?, ?> m) return m; throw new IllegalArgumentException(); }
    private static String text(Map<?, ?> config, String key, boolean required) {
        if (!(config.get(key) instanceof String value) || (required && value.isBlank())) throw new IllegalArgumentException();
        return value;
    }
    private static int number(Map<?, ?> config, String key, int min, int max) {
        if (!(config.get(key) instanceof Integer value) || value < min || value > max) throw new IllegalArgumentException();
        return value;
    }
    private static void endpoint(Map<?, ?> config) {
        String host = text(config, "host", true);
        if (host.length() > 253 || !(host.matches("[A-Za-z0-9.-]+") || host.matches("[A-Fa-f0-9:]+"))) throw new IllegalArgumentException();
        number(config, "port", 1, 65535);
    }
}
