package io.github.wochen5770.talkweave.managed.persistence;

import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlTransactionsTest {
    DataSource source;
    Connection c;
    MysqlOwnership owner;
    MysqlTransactions transactions;
    ResultSet epoch;

    @BeforeEach void prepare() throws Exception {
        source = mock(DataSource.class); c = mock(Connection.class); owner = mock(MysqlOwnership.class);
        var query = mock(PreparedStatement.class); epoch = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(c);
        when(c.getAutoCommit()).thenReturn(true);
        when(c.prepareStatement("SELECT epoch FROM runtime_owner WHERE slot=1 FOR SHARE")).thenReturn(query);
        when(query.executeQuery()).thenReturn(epoch); when(epoch.next()).thenReturn(true); when(epoch.getLong(1)).thenReturn(7L);
        transactions = new MysqlTransactions(source, owner, 7);
    }

    @Test void guardsEpochAndOwnerBeforeCommittingAndReturnsConnection() throws Exception {
        assertEquals("result", transactions.execute(c -> "result"));
        var order = inOrder(owner,c);
        order.verify(owner).check(); order.verify(c).setAutoCommit(false);
        order.verify(c).prepareStatement("SELECT epoch FROM runtime_owner WHERE slot=1 FOR SHARE");
        order.verify(owner).check(); order.verify(c).commit();
        order.verify(c).setAutoCommit(true); order.verify(c).close();
        verify(c,never()).rollback();
    }

    @Test void rollbackOnBusinessRefusalHasNoRetryOrCauseLeak() throws Exception {
        var calls = new AtomicInteger();
        var failure = assertThrows(ManagedProblem.class, () -> transactions.execute(c -> {
            calls.incrementAndGet(); throw new ManagedProblem(ManagedProblem.Code.BACKLOG);
        }));
        assertEquals(ManagedProblem.Code.BACKLOG,failure.code()); assertEquals(1,calls.get());
        verify(c).rollback(); verify(c,never()).commit(); verify(c).setAutoCommit(true);
    }

    @Test void pureSqlDeadlockRetriesOnlyAfterRollbackAndAtMostTwice() throws Exception {
        var calls = new AtomicInteger();
        var failure = assertThrows(ManagedProblem.class, () -> transactions.execute(c -> {
            calls.incrementAndGet(); throw new SQLException("secret-query-value", "40001",1213);
        }));
        assertEquals(3,calls.get()); verify(c,times(3)).rollback(); verify(source,times(3)).getConnection();
        verify(c,never()).commit(); assertNull(failure.getCause());
        assertFalse(failure.toString().contains("secret-query-value"));
    }

    @Test void aRetriedDatabaseTransactionCanSucceed() throws Exception {
        var calls = new AtomicInteger();
        int result = transactions.execute(c -> {
            if (calls.getAndIncrement() == 0) throw new SQLException("deadlock", "40001",1213);
            return 42;
        });
        assertEquals(42, result);
        assertEquals(2,calls.get()); verify(c).rollback(); verify(c).commit();
    }

    @ParameterizedTest @ValueSource(ints={1205,1062,1452,3819,2006})
    void otherSqlFailuresNeverRetry(int code) throws Exception {
        var calls = new AtomicInteger();
        var failure = assertThrows(ManagedProblem.class, () -> transactions.execute(c -> {
            calls.incrementAndGet(); throw new SQLException("secret",code==1062||code==1452?"23000":"HY000",code);
        }));
        assertEquals(1,calls.get()); verify(c).rollback(); assertNull(failure.getCause());
        assertEquals(code==1062||code==1452||code==3819?ManagedProblem.Code.CONFLICT:ManagedProblem.Code.DATABASE_UNAVAILABLE,failure.code());
    }

    @Test void commitFailureIsNeverRetriedEvenWithDeadlockCode() throws Exception {
        doThrow(new SQLException("commit uncertain","40001",1213)).when(c).commit();
        var calls = new AtomicInteger();
        assertThrows(ManagedProblem.class, () -> transactions.execute(c -> calls.incrementAndGet()));
        assertEquals(1,calls.get()); verify(c).rollback(); verify(source).getConnection();
    }

    @Test void failedRollbackAbortsTheBorrowedConnectionWithoutImplicitCommit() throws Exception {
        doThrow(new SQLException("rollback failed")).when(c).rollback();
        var calls = new AtomicInteger();
        assertThrows(ManagedProblem.class, () -> transactions.execute(c -> {
            calls.incrementAndGet(); throw new SQLException("deadlock","40001",1213);
        }));
        assertEquals(1,calls.get()); verify(c).abort(any()); verify(c,never()).setAutoCommit(true);
        verify(owner).close();
        verify(c,never()).commit(); verify(c).close();
    }

    @Test void staleEpochFencesOwnershipBeforeBusinessCallback() throws Exception {
        when(epoch.getLong(1)).thenReturn(8L); var calls = new AtomicInteger();
        assertThrows(ManagedProblem.class, () -> transactions.execute(c -> calls.incrementAndGet()));
        assertEquals(0,calls.get()); verify(owner).close(); verify(c).rollback(); verify(c,never()).commit();
    }

    @Test void confirmedAbortOfCancelledUserConnectionDoesNotFenceOtherUsers() throws Exception {
        doThrow(new SQLException("cancelled socket")).when(c).rollback();
        try {
            assertThrows(ManagedProblem.class, () -> transactions.execute(connection -> {
                Thread.currentThread().interrupt(); throw new SQLException("cancelled worker");
            }));
            verify(c).abort(any()); verify(owner, never()).close();
            verify(c, never()).setAutoCommit(true);
        } finally { Thread.interrupted(); }
    }

    @Test void lossOfOwnershipAfterWorkPreventsCommit() throws Exception {
        doNothing().doThrow(new ManagedProblem(ManagedProblem.Code.DATABASE_UNAVAILABLE)).when(owner).check();
        assertThrows(ManagedProblem.class, () -> transactions.execute(c -> 1));
        verify(c).rollback(); verify(c,never()).commit();
    }

    @Test void preexistingBusinessTransactionIsNotAdopted() throws Exception {
        when(c.getAutoCommit()).thenReturn(false);
        assertThrows(ManagedProblem.class, () -> transactions.execute(c -> 1));
        verify(c,never()).setAutoCommit(anyBoolean()); verify(c,never()).commit();
    }

    @Test void preexistingInterruptDoesNotBorrowAConnection() throws Exception {
        Thread.currentThread().interrupt();
        try { assertThrows(ManagedProblem.class, () -> transactions.execute(c -> 1)); }
        finally { Thread.interrupted(); }
        verify(source,never()).getConnection();
    }

    @Test void generatedIdsUseJdbc64BitKeysAndRejectMissingKeys() throws Exception {
        var insert = mock(PreparedStatement.class); var keys = mock(ResultSet.class);
        when(c.prepareStatement("INSERT synthetic",Statement.RETURN_GENERATED_KEYS)).thenReturn(insert);
        when(insert.executeUpdate()).thenReturn(1); when(insert.getGeneratedKeys()).thenReturn(keys);
        when(keys.next()).thenReturn(true,false); when(keys.getLong(1)).thenReturn(9_007_199_254_740_993L);
        assertEquals(9_007_199_254_740_993L, Sql.insertId(c,"INSERT synthetic","body"));
        verify(insert).setObject(1,"body"); verify(insert).close();
        when(keys.next()).thenReturn(false);
        assertThrows(SQLException.class, () -> Sql.insertId(c,"INSERT synthetic","body"));
    }
}
