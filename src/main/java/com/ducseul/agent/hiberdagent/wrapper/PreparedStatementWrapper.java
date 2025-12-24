package com.ducseul.agent.hiberdagent.wrapper;

import com.ducseul.agent.hiberdagent.config.AgentConfig;
import com.ducseul.agent.hiberdagent.format.SqlFormatter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InvocationHandler that wraps PreparedStatement/CallableStatement to capture
 * parameters and log filled SQL statements.
 */
public class PreparedStatementWrapper implements InvocationHandler {

    private final Object delegate;
    private final String originalSql;

    // 1-based index parameters
    private final Map<Integer, Object> indexedParams = new ConcurrentHashMap<Integer, Object>();
    // Named parameters for CallableStatement
    private final Map<String, Object> namedParams = new ConcurrentHashMap<String, Object>();
    // Batch snapshots
    private final List<Map<Integer, Object>> batchParams = new ArrayList<Map<Integer, Object>>();

    private static final String[] EXECUTE_METHODS = {
        "execute", "executeQuery", "executeUpdate", "executeLargeUpdate"
    };

    private static final String[] SETTER_PREFIXES = {
        "setString", "setInt", "setLong", "setDouble", "setFloat",
        "setBoolean", "setByte", "setShort", "setBigDecimal",
        "setDate", "setTime", "setTimestamp", "setObject",
        "setNull", "setBytes", "setArray", "setBlob", "setClob",
        "setNString", "setNClob", "setRef", "setRowId", "setSQLXML",
        "setURL", "setAsciiStream", "setBinaryStream", "setCharacterStream",
        "setNCharacterStream", "setUnicodeStream"
    };

    public PreparedStatementWrapper(Object delegate, String originalSql) {
        this.delegate = delegate;
        this.originalSql = originalSql;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        try {
            // Handle parameter setters
            if (isSetterMethod(methodName) && args != null && args.length >= 2) {
                captureParameter(methodName, args);
                return method.invoke(delegate, args);
            }

            // Handle clearParameters
            if ("clearParameters".equals(methodName)) {
                indexedParams.clear();
                namedParams.clear();
                return method.invoke(delegate, args);
            }

            // Handle addBatch for PreparedStatement (no args version)
            if ("addBatch".equals(methodName) && (args == null || args.length == 0)) {
                batchParams.add(new HashMap<Integer, Object>(indexedParams));
                return method.invoke(delegate, args);
            }

            // Handle execute methods
            if (isExecuteMethod(methodName)) {
                return handleExecute(method, args);
            }

            // Handle executeBatch
            if ("executeBatch".equals(methodName) || "executeLargeBatch".equals(methodName)) {
                return handleExecuteBatch(method, args);
            }

            // Default: delegate
            return method.invoke(delegate, args);

        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private boolean isSetterMethod(String methodName) {
        for (String prefix : SETTER_PREFIXES) {
            if (methodName.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExecuteMethod(String methodName) {
        for (String execMethod : EXECUTE_METHODS) {
            if (methodName.equals(execMethod)) {
                return true;
            }
        }
        return false;
    }

    private void captureParameter(String methodName, Object[] args) {
        Object firstArg = args[0];
        Object value = args.length > 1 ? args[1] : null;

        // Handle setNull specially
        if ("setNull".equals(methodName)) {
            value = null;
        }

        if (firstArg instanceof Integer) {
            // Index-based setter
            int index = (Integer) firstArg;
            indexedParams.put(index, value);
        } else if (firstArg instanceof String) {
            // Named parameter (CallableStatement)
            String paramName = (String) firstArg;
            namedParams.put(paramName, value);
        }
    }

    private Object handleExecute(Method method, Object[] args) throws Throwable {
        long startTime = System.currentTimeMillis();
        try {
            return method.invoke(delegate, args);
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            logIfNeeded(elapsed, originalSql, indexedParams, namedParams);
        }
    }

    private Object handleExecuteBatch(Method method, Object[] args) throws Throwable {
        long startTime = System.currentTimeMillis();
        try {
            return method.invoke(delegate, args);
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            if (AgentConfig.shouldLog(elapsed)) {
                if (batchParams.isEmpty()) {
                    // No captured batch params, log with current params
                    logIfNeeded(elapsed, originalSql, indexedParams, namedParams);
                } else {
                    // Log batch execution summary
                    System.out.println("[SQL] (took=" + elapsed + "ms, batch=" + batchParams.size() + " statements)");
                    // Log first statement as example
                    String formattedSql = SqlFormatter.format(originalSql, batchParams.get(0), namedParams);
                    System.out.println("[SQL] First batch item: " + formattedSql);
                    if (AgentConfig.isLogStack()) {
                        String stack = SqlFormatter.formatStackTrace(AgentConfig.getMaxStackDepth());
                        if (!stack.isEmpty()) {
                            System.out.println("[STACK] " + stack);
                        }
                    }
                }
                batchParams.clear();
            }
        }
    }

    private void logIfNeeded(long elapsed, String sql, Map<Integer, Object> indexed, Map<String, Object> named) {
        if (AgentConfig.shouldLog(elapsed)) {
            String formattedSql = SqlFormatter.format(sql, indexed, named);
            System.out.println("[SQL] (took=" + elapsed + "ms) " + formattedSql);

            if (AgentConfig.isLogStack()) {
                String stack = SqlFormatter.formatStackTrace(AgentConfig.getMaxStackDepth());
                if (!stack.isEmpty()) {
                    System.out.println("[STACK] " + stack);
                }
            }
        }
    }

    /**
     * Creates a proxy wrapper for a PreparedStatement.
     */
    public static PreparedStatement wrap(PreparedStatement stmt, String sql) {
        if (stmt == null) {
            return null;
        }

        Class<?>[] interfaces = getInterfaces(stmt);
        PreparedStatementWrapper handler = new PreparedStatementWrapper(stmt, sql);

        return (PreparedStatement) Proxy.newProxyInstance(
            stmt.getClass().getClassLoader(),
            interfaces,
            handler
        );
    }

    /**
     * Creates a proxy wrapper for a CallableStatement.
     */
    public static CallableStatement wrapCallable(CallableStatement stmt, String sql) {
        if (stmt == null) {
            return null;
        }

        Class<?>[] interfaces = getInterfaces(stmt);
        PreparedStatementWrapper handler = new PreparedStatementWrapper(stmt, sql);

        return (CallableStatement) Proxy.newProxyInstance(
            stmt.getClass().getClassLoader(),
            interfaces,
            handler
        );
    }

    private static Class<?>[] getInterfaces(Object obj) {
        List<Class<?>> interfaces = new ArrayList<Class<?>>();
        Class<?> current = obj.getClass();

        while (current != null) {
            for (Class<?> iface : current.getInterfaces()) {
                if (!interfaces.contains(iface)) {
                    interfaces.add(iface);
                }
            }
            current = current.getSuperclass();
        }

        // Ensure standard JDBC interfaces are included
        if (!interfaces.contains(PreparedStatement.class)) {
            if (obj instanceof PreparedStatement) {
                interfaces.add(PreparedStatement.class);
            }
        }
        if (!interfaces.contains(CallableStatement.class)) {
            if (obj instanceof CallableStatement) {
                interfaces.add(CallableStatement.class);
            }
        }
        if (!interfaces.contains(Statement.class)) {
            if (obj instanceof Statement) {
                interfaces.add(Statement.class);
            }
        }

        return interfaces.toArray(new Class<?>[0]);
    }
}
