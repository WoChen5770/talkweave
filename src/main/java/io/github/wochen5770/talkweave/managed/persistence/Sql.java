package io.github.wochen5770.talkweave.managed.persistence;

import java.sql.*;

final class Sql {
    private Sql() { }
    static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        var statement = c.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
        return statement;
    }
    static int update(Connection c, String sql, Object... values) throws SQLException {
        try (var statement = prepare(c, sql, values)) { return statement.executeUpdate(); }
    }
    static long scalar(Connection c, String sql, Object... values) throws SQLException {
        try (var statement = prepare(c, sql, values); var rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
    static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column); return rows.wasNull() ? null : value;
    }
    static void audit(Connection c, String action, String target, long now) throws SQLException {
        update(c, "INSERT INTO audit_event(actor,action,target,result,occurred_at) VALUES ('administrator',?,?, 'SUCCEEDED',?)", action, target, now);
    }
}