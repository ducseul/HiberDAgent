package com.ducseul.agent.hiberdagent.format;

import com.ducseul.agent.hiberdagent.wrapper.PreparedStatementWrapper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to reconstruct SQL with parameters filled in.
 * Formats output for Oracle database compatibility.
 */
public final class SqlFormatter {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile(":(\\w+)");

    private SqlFormatter() {
    }

    /**
     * Reconstructs the SQL by replacing ? placeholders with values from indexedParams.
     * Then applies named parameter substitution if namedParams is not empty.
     *
     * @param originalSql   the original SQL with ? placeholders
     * @param indexedParams map of 1-based index to parameter value
     * @param namedParams   map of parameter name to value (for CallableStatement)
     * @return the SQL string with parameters filled in
     */
    public static String format(String originalSql, Map<Integer, Object> indexedParams, Map<String, Object> namedParams) {
        if (originalSql == null) {
            return "NULL";
        }

        String result = replaceIndexedParams(originalSql, indexedParams);

        if (namedParams != null && !namedParams.isEmpty()) {
            result = replaceNamedParams(result, namedParams);
        }

        return result;
    }

    private static String replaceIndexedParams(String sql, Map<Integer, Object> params) {
        if (params == null || params.isEmpty()) {
            return sql;
        }

        StringBuilder sb = new StringBuilder();
        int paramIndex = 1;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);

            // Skip string literals
            if (c == '\'') {
                int end = findEndOfStringLiteral(sql, i);
                sb.append(sql, i, end);
                i = end;
                continue;
            }

            if (c == '?') {
                Object value = params.get(paramIndex);
                sb.append(formatValue(value));
                paramIndex++;
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static int findEndOfStringLiteral(String sql, int start) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // Check for escaped quote ''
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    private static String replaceNamedParams(String sql, Map<String, Object> namedParams) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = NAMED_PARAM_PATTERN.matcher(sql);
        while (matcher.find()) {
            String paramName = matcher.group(1);
            Object value = namedParams.get(paramName);
            if (value != null || namedParams.containsKey(paramName)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(formatValue(value)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Formats a parameter value for SQL output (Oracle compatible).
     */
    public static String formatValue(Object value) {
        if (value == null || value == PreparedStatementWrapper.NULL_VALUE) {
            return "NULL";
        }

        if (value instanceof String) {
            return formatString((String) value);
        }

        if (value instanceof Character) {
            return formatString(value.toString());
        }

        if (value instanceof Number) {
            return value.toString();
        }

        if (value instanceof Boolean) {
            return value.toString();
        }

        if (value instanceof java.sql.Timestamp) {
            return formatTimestamp((java.sql.Timestamp) value);
        }

        if (value instanceof java.sql.Date) {
            return formatSqlDate((java.sql.Date) value);
        }

        if (value instanceof java.sql.Time) {
            return formatSqlTime((java.sql.Time) value);
        }

        if (value instanceof Date) {
            return formatDate((Date) value);
        }

        if (value instanceof byte[]) {
            return formatByteArray((byte[]) value);
        }

        // Fallback: treat as string
        return formatString(value.toString());
    }

    private static String formatString(String s) {
        // Escape single quotes by doubling them
        String escaped = s.replace("'", "''");
        return "'" + escaped + "'";
    }

    private static String formatTimestamp(java.sql.Timestamp ts) {
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
        return "TO_TIMESTAMP('" + sdf.format(ts) + "', 'YYYY-MM-DD HH24:MI:SS')";
    }

    private static String formatSqlDate(java.sql.Date date) {
        return "TO_DATE('" + date.toString() + "', 'YYYY-MM-DD')";
    }

    private static String formatSqlTime(java.sql.Time time) {
        return "TO_TIMESTAMP('1970-01-01 " + time.toString() + "', 'YYYY-MM-DD HH24:MI:SS')";
    }

    private static String formatDate(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
        return "TO_TIMESTAMP('" + sdf.format(date) + "', 'YYYY-MM-DD HH24:MI:SS')";
    }

    private static String formatByteArray(byte[] bytes) {
        StringBuilder sb = new StringBuilder("X'");
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        sb.append("'");
        return sb.toString();
    }

    /**
     * Formats the stack trace for logging.
     */
    public static String formatStackTrace(int maxDepth) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();

        int count = 0;
        boolean started = false;

        for (StackTraceElement element : stack) {
            String className = element.getClassName();

            // Skip agent classes and java internal classes
            if (className.startsWith("com.ducseul.agent.hiberdagent.") ||
                className.startsWith("java.lang.Thread") ||
                className.startsWith("sun.reflect.") ||
                className.startsWith("java.lang.reflect.") ||
                className.startsWith("jdk.internal.") ||
                className.contains("$Proxy")) {
                continue;
            }

            // Skip JDBC driver internals but mark that we should start capturing
            if (className.startsWith("java.sql.") ||
                className.contains("jdbc") ||
                className.contains("JDBC")) {
                started = true;
                continue;
            }

            if (started || count > 0) {
                if (count > 0) {
                    sb.append(" <- ");
                }
                sb.append(element.getClassName())
                  .append(".")
                  .append(element.getMethodName())
                  .append("(")
                  .append(element.getFileName())
                  .append(":")
                  .append(element.getLineNumber())
                  .append(")");
                count++;

                if (count >= maxDepth) {
                    sb.append(" ...");
                    break;
                }
            }
        }

        return sb.toString();
    }
}
