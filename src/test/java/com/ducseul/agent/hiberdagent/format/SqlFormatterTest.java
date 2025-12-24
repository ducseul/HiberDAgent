package com.ducseul.agent.hiberdagent.format;

import org.junit.Test;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for SqlFormatter.
 */
public class SqlFormatterTest {

    @Test
    public void testSimpleIndexedParameters() {
        String sql = "SELECT * FROM users WHERE id = ? AND name = ?";
        Map<Integer, Object> params = new HashMap<Integer, Object>();
        params.put(1, 123);
        params.put(2, "alice");

        String result = SqlFormatter.format(sql, params, null);

        assertEquals("SELECT * FROM users WHERE id = 123 AND name = 'alice'", result);
    }

    @Test
    public void testNullParameter() {
        String sql = "INSERT INTO users (name, email) VALUES (?, ?)";
        Map<Integer, Object> params = new HashMap<Integer, Object>();
        params.put(1, "bob");
        params.put(2, null);

        String result = SqlFormatter.format(sql, params, null);

        assertEquals("INSERT INTO users (name, email) VALUES ('bob', NULL)", result);
    }

    @Test
    public void testStringWithSingleQuotes() {
        String sql = "INSERT INTO users (name) VALUES (?)";
        Map<Integer, Object> params = new HashMap<Integer, Object>();
        params.put(1, "O'Brien");

        String result = SqlFormatter.format(sql, params, null);

        assertEquals("INSERT INTO users (name) VALUES ('O''Brien')", result);
    }

    @Test
    public void testTimestamp() {
        String sql = "SELECT * FROM events WHERE created_at > ?";
        Map<Integer, Object> params = new HashMap<Integer, Object>();
        Timestamp ts = Timestamp.valueOf("2024-01-15 10:30:00");
        params.put(1, ts);

        String result = SqlFormatter.format(sql, params, null);

        // Now uses Oracle TO_TIMESTAMP format
        assertEquals("SELECT * FROM events WHERE created_at > TO_TIMESTAMP('2024-01-15 10:30:00', 'YYYY-MM-DD HH24:MI:SS')", result);
    }

    @Test
    public void testSqlDate() {
        String sql = "SELECT * FROM events WHERE event_date = ?";
        Map<Integer, Object> params = new HashMap<Integer, Object>();
        java.sql.Date date = java.sql.Date.valueOf("2024-01-15");
        params.put(1, date);

        String result = SqlFormatter.format(sql, params, null);

        // Now uses Oracle TO_DATE format
        assertEquals("SELECT * FROM events WHERE event_date = TO_DATE('2024-01-15', 'YYYY-MM-DD')", result);
    }

    @Test
    public void testBooleanParameter() {
        String sql = "SELECT * FROM users WHERE active = ?";
        Map<Integer, Object> params = new HashMap<Integer, Object>();
        params.put(1, true);

        String result = SqlFormatter.format(sql, params, null);

        assertEquals("SELECT * FROM users WHERE active = true", result);
    }

    @Test
    public void testByteArrayParameter() {
        String sql = "INSERT INTO files (data) VALUES (?)";
        Map<Integer, Object> params = new HashMap<Integer, Object>();
        params.put(1, new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});

        String result = SqlFormatter.format(sql, params, null);

        assertEquals("INSERT INTO files (data) VALUES (X'DEADBEEF')", result);
    }

    @Test
    public void testNamedParameters() {
        String sql = "SELECT * FROM users WHERE id = :userId AND status = :status";
        Map<Integer, Object> indexed = new HashMap<Integer, Object>();
        Map<String, Object> named = new HashMap<String, Object>();
        named.put("userId", 42);
        named.put("status", "active");

        String result = SqlFormatter.format(sql, indexed, named);

        assertEquals("SELECT * FROM users WHERE id = 42 AND status = 'active'", result);
    }

    @Test
    public void testMixedParameters() {
        String sql = "SELECT * FROM users WHERE id = ? AND name = ?";
        Map<Integer, Object> params = new HashMap<Integer, Object>();
        params.put(1, 100);
        params.put(2, "test");

        String result = SqlFormatter.format(sql, params, new HashMap<String, Object>());

        assertEquals("SELECT * FROM users WHERE id = 100 AND name = 'test'", result);
    }

    @Test
    public void testSqlWithStringLiterals() {
        // The ? inside the string literal should NOT be replaced
        String sql = "SELECT * FROM users WHERE status = '?' AND id = ?";
        Map<Integer, Object> params = new HashMap<Integer, Object>();
        params.put(1, 42);

        String result = SqlFormatter.format(sql, params, null);

        assertEquals("SELECT * FROM users WHERE status = '?' AND id = 42", result);
    }

    @Test
    public void testEmptyParams() {
        String sql = "SELECT * FROM users";
        Map<Integer, Object> params = new HashMap<Integer, Object>();

        String result = SqlFormatter.format(sql, params, null);

        assertEquals("SELECT * FROM users", result);
    }

    @Test
    public void testNullSql() {
        String result = SqlFormatter.format(null, new HashMap<Integer, Object>(), null);
        assertEquals("NULL", result);
    }

    @Test
    public void testFormatValueDirectly() {
        assertEquals("NULL", SqlFormatter.formatValue(null));
        assertEquals("'hello'", SqlFormatter.formatValue("hello"));
        assertEquals("42", SqlFormatter.formatValue(42));
        assertEquals("3.14", SqlFormatter.formatValue(3.14));
        assertEquals("true", SqlFormatter.formatValue(true));
        assertEquals("'a'", SqlFormatter.formatValue('a'));
    }
}
