package io.github.wochen5770.talkweave.managed.config;

import java.nio.file.*;
import java.sql.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalIntegrationTargetTest {
    @TempDir Path directory;

    private static Properties approvals() {
        var p = new Properties();
        p.setProperty("talkweave.it.enabled", "true");
        p.setProperty("talkweave.it.mysql-schema", "talkweave_test");
        p.setProperty("talkweave.it.redis-prefix", "talkweave");
        p.setProperty("talkweave.it.mysql-version", "8.0.44");
        p.setProperty("talkweave.it.redis-version", "7.2.12");
        return p;
    }
    private static ExternalIntegrationTarget target(Properties p) {
        return ExternalIntegrationTarget.approved(ExternalServicesTest.read(ExternalServicesTest.valid()), p);
    }

    @Test void noEnablementOrPathCannotLoadProductionDefaults() {
        assertThrows(IllegalArgumentException.class, () -> ExternalIntegrationTarget.load(new Properties()));
        assertThrows(IllegalArgumentException.class, () -> ExternalIntegrationTarget.load(approvals()));
    }

    @ParameterizedTest @ValueSource(strings={"mysql-schema", "redis-prefix", "mysql-version", "redis-version"})
    void everyTargetApprovalIsRequired(String key) {
        var p = approvals(); p.remove("talkweave.it." + key);
        assertThrows(IllegalArgumentException.class, () -> target(p));
    }

    @ParameterizedTest @ValueSource(strings={"mysql-schema", "redis-prefix"})
    void mismatchedTargetsAreRejectedWithoutNetwork(String key) {
        var p = approvals(); p.setProperty("talkweave.it." + key, "unapproved-secret-value");
        var failure = assertThrows(IllegalArgumentException.class, () -> target(p));
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("unapproved-secret-value"));
    }

    @Test void keysAndFixtureLabelsAreRandomAndConfinedToApprovedSubprefix() {
        var first = target(approvals()); var second = target(approvals());
        assertNotEquals(first.fixtureLabel(), second.fixtureLabel());
        assertNotEquals(first.redisKey("history-v1"), second.redisKey("history-v1"));
        assertTrue(first.redisKey("history-v1").matches("talkweave:it:[0-9a-f-]{36}:history-v1"));
        assertThrows(IllegalArgumentException.class, () -> first.redisKey("other:project"));
        assertThrows(IllegalArgumentException.class, () -> first.redisKey("*"));
        assertFalse(first.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, first::requireSchemaInitialization);
        assertThrows(IllegalArgumentException.class, first::requireSchemaUpgrade);
        var approved = approvals(); approved.setProperty("talkweave.it.allow-schema-initialization", "true");
        approved.setProperty("talkweave.it.allow-schema-upgrade", "true");
        assertDoesNotThrow(target(approved)::requireSchemaInitialization);
        assertDoesNotThrow(target(approved)::requireSchemaUpgrade);
    }

    @Test void redisVersionMustMatchExactApprovedVersion() {
        var target = target(approvals());
        assertDoesNotThrow(() -> target.verifyRedis("# Server\r\nredis_version:7.2.12\r\n"));
        assertThrows(IllegalArgumentException.class, () -> target.verifyRedis("redis_version:7.2.13\r\n"));
        assertThrows(IllegalArgumentException.class, () -> target.verifyRedis(""));
    }

    @Test void mysqlTargetAndVersionAreVerifiedReadOnly() throws Exception {
        var c = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        var s = mock(Statement.class); var rows = mock(ResultSet.class);
        when(c.getCatalog()).thenReturn("talkweave_test");
        when(c.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductVersion()).thenReturn("8.0.44");
        when(metadata.getDriverVersion()).thenReturn("mysql-connector-j-9.6.0");
        when(c.createStatement()).thenReturn(s);
        when(s.executeQuery("SELECT DATABASE()")).thenReturn(rows);
        when(rows.next()).thenReturn(true); when(rows.getString(1)).thenReturn("talkweave_test");
        target(approvals()).verifyMysql(c);
        verify(s).executeQuery("SELECT DATABASE()");
        when(c.getCatalog()).thenReturn("another_database");
        assertThrows(IllegalArgumentException.class, () -> target(approvals()).verifyMysql(c));
        verify(c, times(1)).createStatement();
    }

    @Test void businessRowsRefuseSchemaTestBeforeAnyMutation() throws Exception {
        var c = mock(Connection.class); var s = mock(Statement.class);
        var objects = mock(ResultSet.class); var data = mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s);
        when(s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"))
                .thenReturn(objects);
        when(objects.next()).thenReturn(true, false); when(objects.getString(1)).thenReturn("administrator");
        when(s.executeQuery("SELECT 1 FROM `administrator` LIMIT 1")).thenReturn(data);
        when(data.next()).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> target(approvals()).requireNoBusinessData(c));
        verify(s, never()).executeUpdate(anyString());
        verify(s, never()).execute(anyString());
        verify(c, never()).commit();
    }

    @Test void explicitFileDoesNotInheritAmbientOverrides() throws Exception {
        Path file = directory.resolve("synthetic.yml");
        Files.writeString(file, """
                managed:
                  mysql:
                    host: localhost
                    schema: talkweave_test
                    username: synthetic
                    password: 'secret-no-output'
                    ssl-mode: DISABLED
                  redis:
                    host: localhost
                    tls: false
                """);
        var p = approvals(); p.setProperty("talkweave.it.config", file.toString());
        // Not passed to the isolated MockEnvironment even if supplied on a Maven/production command line.
        p.setProperty("managed.mysql.host", "unapproved-host");
        var loaded = ExternalIntegrationTarget.load(p);
        assertEquals("localhost", loaded.config().mysql().host());
        assertEquals(ExternalServices.SslMode.DISABLED, loaded.config().mysql().sslMode());
        assertFalse(loaded.config().redis().tls());
    }

    @ParameterizedTest @ValueSource(strings={"a: 1\na: secret-value", "a: &anchor [1]\nb: *anchor", "password: [unclosed-secret"})
    void malformedYamlNeverLeaksOriginalSource(String yaml) throws Exception {
        Path file = directory.resolve("invalid.yml"); Files.writeString(file, yaml);
        var p = approvals(); p.setProperty("talkweave.it.config", file.toString());
        var failure = assertThrows(IllegalArgumentException.class, () -> ExternalIntegrationTarget.load(p));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("secret"));
    }
}
