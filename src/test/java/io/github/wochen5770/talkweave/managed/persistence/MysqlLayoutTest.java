package io.github.wochen5770.talkweave.managed.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlLayoutTest {
    private static String resource(String name) throws Exception {
        try (var input = MysqlLayout.class.getResourceAsStream("/db/mysql/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
    private static String hash(int version) throws Exception {
        String source = resource("V001__managed.sql");
        return MysqlOwnership.digest(version == 1 ? source : source + "\n" + resource("V002__conversation_pagination.sql"));
    }

    @Test void executedV1IsImmutableAndV2IsOneAdditiveStatement() throws Exception {
        assertEquals("b07695d51959e5955c6df96c7d9b9a50a944362e956c4a198d7e99c65f65bfc5", hash(1));
        String upgrade = resource("V002__conversation_pagination.sql");
        assertEquals(1, upgrade.chars().filter(c -> c == ';').count());
        assertTrue(upgrade.startsWith("ALTER TABLE conversation\n"));
        assertTrue(upgrade.contains("ADD COLUMN sequence BIGINT NOT NULL AUTO_INCREMENT"));
        assertTrue(upgrade.contains("ADD UNIQUE KEY conversation_sequence (sequence)"));
        assertTrue(upgrade.contains("ADD KEY user_conversations (user_id, sequence)"));
        assertFalse(upgrade.matches("(?is).*(DROP|TRUNCATE|DELETE|RENAME|MODIFY).*"));
    }

    @ParameterizedTest @ValueSource(ints={1,2})
    void onlyCompleteExactKnownVersionsAreAccepted(int version) throws Exception {
        String hash = hash(version);
        assertDoesNotThrow(() -> MysqlLayout.validateMarker(version, "READY", hash, "layout", "layout"));
        for (String state : new String[]{"INITIALIZING", "FAILED", "ready"}) {
            assertThrows(ManagedProblem.class, () -> MysqlLayout.validateMarker(version, state, hash, "layout", "layout"));
        }
        assertThrows(ManagedProblem.class, () -> MysqlLayout.validateMarker(version, "READY", "tampered", "layout", "layout"));
        assertThrows(ManagedProblem.class, () -> MysqlLayout.validateMarker(version, "READY", hash, "before", "after"));
        assertThrows(ManagedProblem.class, () -> MysqlLayout.validateMarker(version, "READY", hash, null, "layout"));
    }

    @ParameterizedTest @ValueSource(longs={-1,0,3,Long.MAX_VALUE})
    void unknownVersionsFailClosed(long version) throws Exception {
        String hash = hash(2);
        var failure = assertThrows(ManagedProblem.class, () -> MysqlLayout.validateMarker(version, "READY", hash, "layout", "layout"));
        assertEquals(ManagedProblem.Code.INCOMPATIBLE_LAYOUT, failure.code());
        assertNull(failure.getCause());
    }

    @Test void upgradeCannotRunInsideABusinessTransaction() throws Exception {
        var c = mock(Connection.class); var owner = mock(MysqlOwnership.class);
        when(c.getAutoCommit()).thenReturn(false);
        assertThrows(ManagedProblem.class, () -> MysqlLayout.upgrade(c, owner));
        verify(c, never()).prepareStatement(anyString());
        verify(c, never()).createStatement();
    }

    @Test void failureToPersistMarkerPreventsDdl() throws Exception {
        var c = mock(Connection.class); var owner = mock(MysqlOwnership.class);
        var marker = mock(PreparedStatement.class);
        when(c.getAutoCommit()).thenReturn(true);
        when(c.prepareStatement(anyString())).thenReturn(marker);
        when(marker.executeUpdate()).thenReturn(0);
        assertThrows(ManagedProblem.class, () -> MysqlLayout.upgrade(c, owner));
        verify(c, never()).createStatement();
    }

    @Test void ddlFailureLeavesDurableInitializingMarkerAndDoesNotAdvanceEpoch() throws Exception {
        var c = mock(Connection.class); var owner = mock(MysqlOwnership.class);
        var marker = mock(PreparedStatement.class); var ddl = mock(Statement.class);
        when(c.getAutoCommit()).thenReturn(true);
        when(c.prepareStatement(anyString())).thenReturn(marker);
        when(marker.executeUpdate()).thenReturn(1);
        when(c.createStatement()).thenReturn(ddl);
        when(ddl.execute(anyString())).thenThrow(new SQLException("synthetic DDL failure"));
        assertThrows(SQLException.class, () -> MysqlLayout.upgrade(c, owner));
        var order = inOrder(owner, c, marker, ddl);
        order.verify(owner).check();
        order.verify(c).prepareStatement(startsWith("UPDATE managed_schema SET version=2,state='INITIALIZING'"));
        order.verify(marker).executeUpdate();
        order.verify(owner).check();
        order.verify(ddl).execute(startsWith("ALTER TABLE conversation"));
        verify(c, times(1)).prepareStatement(anyString()); // No READY, epoch update, or cleanup.
        verify(c, never()).rollback(); // Do not claim DDL was undone.
    }

    @Test void lossOfOwnershipAfterMarkerDoesNotStartDdl() throws Exception {
        var c = mock(Connection.class); var owner = mock(MysqlOwnership.class);
        var marker = mock(PreparedStatement.class);
        when(c.getAutoCommit()).thenReturn(true);
        when(c.prepareStatement(anyString())).thenReturn(marker);
        when(marker.executeUpdate()).thenReturn(1);
        doNothing().doThrow(new ManagedProblem(ManagedProblem.Code.DATABASE_UNAVAILABLE)).when(owner).check();
        assertThrows(ManagedProblem.class, () -> MysqlLayout.upgrade(c, owner));
        verify(c, never()).createStatement();
        verify(c, times(1)).prepareStatement(anyString());
    }

    @Test void failureAfterDdlStillDoesNotPublishReady() throws Exception {
        var c = mock(Connection.class); var owner = mock(MysqlOwnership.class);
        var marker = mock(PreparedStatement.class); var ddl = mock(Statement.class);
        when(c.getAutoCommit()).thenReturn(true);
        when(c.prepareStatement(anyString())).thenReturn(marker);
        when(marker.executeUpdate()).thenReturn(1);
        when(c.createStatement()).thenReturn(ddl).thenThrow(new SQLException("synthetic fingerprint failure"));
        assertThrows(SQLException.class, () -> MysqlLayout.upgrade(c, owner));
        verify(ddl).execute(startsWith("ALTER TABLE conversation"));
        verify(c, times(1)).prepareStatement(anyString());
    }
}
