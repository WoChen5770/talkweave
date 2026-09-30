package io.github.wochen5770.talkweave.managed.config;

import io.github.wochen5770.talkweave.managed.persistence.MysqlConnections;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the production additive DDL on connection-private synthetic rows; never resets a schema. */
class ExternalPaginationIT {
    @Test void additiveDdlPreservesRowsAndGivesStableScopedInt64Cursors() {
        String phase = "CONFIG";
        try {
            var target = ExternalIntegrationTarget.load();
            try (var c = MysqlConnections.dedicated(target.config().mysql()); var s = c.createStatement()) {
                phase = "PREFLIGHT";
                target.verifyMysql(c);
                // TEMPORARY tables cannot carry FKs. Actual FK retention is separately verified by ExternalSchemaIT.
                s.execute("CREATE TEMPORARY TABLE tw_pagination_fixture (id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, "
                        + "created_at BIGINT NOT NULL, body TEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin");
                String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
                insert(c,"one",a,10,"原文🙂e\u0301"); insert(c,"two",a,10,"第二轮\n保持原样"); insert(c,"other",b,10,"其他用户");
                var original = bodies(c);
                phase = "ADDITIVE_DDL";
                try (var input = getClass().getResourceAsStream("/db/mysql/V002__conversation_pagination.sql")) {
                    assertNotNull(input);
                    String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(ddl.startsWith("ALTER TABLE conversation"));
                    s.execute(ddl.replace("ALTER TABLE conversation", "ALTER TABLE tw_pagination_fixture"));
                }
                assertEquals(original, bodies(c));
                Map<String,Long> assigned = sequences(c);
                assertEquals(3, new HashSet<>(assigned.values()).size());
                assertTrue(assigned.values().stream().allMatch(sequence -> sequence > 0));
                var firstPage = page(c,a,Long.MAX_VALUE,1);
                assertEquals(1, firstPage.size());
                long cursor = firstPage.getFirst();
                insert(c,"newer",a,11,"新行"); // Does not move the already-issued cursor or reassign old rows.
                var secondPage = page(c,a,cursor,1);
                assertEquals(1, secondPage.size());
                assertTrue(secondPage.getFirst() < cursor);
                assertFalse(secondPage.contains(assigned.get("other")));
                assigned.forEach((id, sequence) -> assertEquals(sequence, uncheckedSequence(c,id)));
                phase = "INT64_CURSOR";
                try (var insert = c.prepareStatement("INSERT INTO tw_pagination_fixture(id,user_id,created_at,body,sequence) VALUES ('large',?,12,'synthetic',?)")) {
                    insert.setString(1,a); insert.setLong(2,9_007_199_254_740_993L); insert.executeUpdate();
                }
                assertEquals(9_007_199_254_740_993L, page(c,a,Long.MAX_VALUE,1).getFirst());
                assertFalse(page(c,a,9_007_199_254_740_993L,10).contains(9_007_199_254_740_993L));
            }
            System.out.println("MYSQL PAGINATION productionV2Ddl=PASS temporaryRowsPreserved=PASS scopedCursor=PASS int64=PASS persistentObjectsWritten=0");
        } catch (Throwable failure) {
            String code = failure instanceof SQLException sql ? "SQL_" + sql.getErrorCode() : failure.getClass().getSimpleName();
            fail("External pagination check failed: " + phase + " (" + code + ")");
        }
    }
    private static void insert(Connection c,String id,String user,long at,String body) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO tw_pagination_fixture(id,user_id,created_at,body) VALUES (?,?,?,?)")) {
            s.setString(1,id); s.setString(2,user); s.setLong(3,at); s.setString(4,body); s.executeUpdate();
        }
    }
    private static Map<String,String> bodies(Connection c) throws SQLException {
        var result = new TreeMap<String,String>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,user_id,created_at,body FROM tw_pagination_fixture")) {
            while(r.next()) result.put(r.getString(1),r.getString(2)+"|"+r.getLong(3)+"|"+r.getString(4));
        }
        return result;
    }
    private static Map<String,Long> sequences(Connection c) throws SQLException {
        var result = new TreeMap<String,Long>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,sequence FROM tw_pagination_fixture")) {
            while(r.next()) result.put(r.getString(1),r.getLong(2));
        }
        return result;
    }
    private static Long uncheckedSequence(Connection c,String id) {
        try { return sequences(c).get(id); } catch (SQLException failure) { throw new IllegalStateException("Synthetic lookup failed"); }
    }
    private static List<Long> page(Connection c,String user,long before,int limit) throws SQLException {
        var result = new ArrayList<Long>();
        try (var s = c.prepareStatement("SELECT sequence FROM tw_pagination_fixture WHERE user_id=? AND sequence<? ORDER BY sequence DESC LIMIT ?")) {
            s.setString(1,user); s.setLong(2,before); s.setInt(3,limit);
            try(var r=s.executeQuery()) { while(r.next()) result.add(r.getLong(1)); }
        }
        return result;
    }
}
