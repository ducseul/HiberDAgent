package com.ducseul.agent.hiberdagent.wrapper;

import com.ducseul.agent.hiberdagent.config.AgentConfig;
import com.ducseul.agent.hiberdagent.format.SqlFormatter;
import com.ducseul.agent.hiberdagent.log.SqlLogWriter;

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

    /**
     * Sentinel object to represent SQL NULL in ConcurrentHashMap (which doesn't
     * allow null values).
     */
    public static final Object NULL_VALUE = new Object() {
        @Override
        public String toString() {
            return "NULL";
        }
    };

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
        long invokeNum = AgentConfig.incrementInvoke();

        // Debug: log every 1000th invocation to stderr
        if (invokeNum % 1000 == 0) {
            AgentConfig.debug("invoke #" + invokeNum + " method=" + methodName);
        }

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
                AgentConfig.incrementExecute();
                AgentConfig.debug("execute #" + AgentConfig.incrementExecute() + " sql=" + originalSql);
                return handleExecute(method, args);
            }

            // Handle executeBatch
            if ("executeBatch".equals(methodName) || "executeLargeBatch".equals(methodName)) {
                AgentConfig.incrementExecute();
                return handleExecuteBatch(method, args);
            }

            // Default: delegate
            return method.invoke(delegate, args);

        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        } catch (Throwable t) {
            // Log any unexpected errors in the agent itself - but DON'T re-throw
            // Re-throwing might cause the caller to abandon this proxy
            SqlLogWriter.getInstance().writeError(
                    "Unexpected error in PreparedStatementWrapper.invoke() for method '" + methodName + "'", t);
            // Try to delegate anyway
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
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

        // Handle setNull specially - mark as NULL_VALUE
        if ("setNull".equals(methodName)) {
            value = NULL_VALUE;
        }

        // ConcurrentHashMap doesn't allow null values, use NULL_VALUE sentinel
        if (value == null) {
            value = NULL_VALUE;
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
            try {
                long elapsed = System.currentTimeMillis() - startTime;
                logIfNeeded(elapsed, originalSql, indexedParams, namedParams);
            } catch (Throwable t) {
                SqlLogWriter.getInstance().writeError("Error in handleExecute logging", t);
            }
        }
    }

    private Object handleExecuteBatch(Method method, Object[] args) throws Throwable {
        long startTime = System.currentTimeMillis();
        try {
            return method.invoke(delegate, args);
        } finally {
            try {
                long elapsed = System.currentTimeMillis() - startTime;
                if (AgentConfig.shouldLog(elapsed)) {
                    SqlLogWriter logger = SqlLogWriter.getInstance();
                    if (batchParams.isEmpty()) {
                        // No captured batch params, log with current params
                        logIfNeeded(elapsed, originalSql, indexedParams, namedParams);
                    } else {
                        // Log batch execution summary
                        logger.writeLine("[SQL] (took=" + elapsed + "ms, batch=" + batchParams.size() + " statements)");
                        // Log first statement as example
                        String formattedSql = SqlFormatter.format(originalSql, batchParams.get(0), namedParams);
                        logger.writeLine("[SQL] First batch item: " + formattedSql);
                        if (AgentConfig.isLogStack()) {
                            String stack = SqlFormatter.formatStackTrace(AgentConfig.getMaxStackDepth());
                            if (!stack.isEmpty()) {
                                logger.writeLine("[STACK] " + stack);
                            }
                        }
                    }
                    batchParams.clear();
                }
            } catch (Throwable t) {
                SqlLogWriter.getInstance().writeError("Error in handleExecuteBatch logging", t);
            }
        }
    }

    private void logIfNeeded(long elapsed, String sql, Map<Integer, Object> indexed, Map<String, Object> named) {
        if (AgentConfig.shouldLog(elapsed)) {
            SqlLogWriter logger = SqlLogWriter.getInstance();
            String formattedSql = SqlFormatter.format(sql, indexed, named);
            logger.writeLine("[SQL] (took=" + elapsed + "ms) " + formattedSql);

            if (AgentConfig.isLogStack()) {
                String stack = SqlFormatter.formatStackTrace(AgentConfig.getMaxStackDepth());
                if (!stack.isEmpty()) {
                    logger.writeLine("[STACK] " + stack);
                }
            }
        }
    }

    /**
     * Creates a proxy wrapper for a PreparedStatement.
     * Returns the original statement if wrapping fails.
     * Avoids double-wrapping if already wrapped.
     */
    public static PreparedStatement wrap(PreparedStatement stmt, String sql) {
        if (stmt == null) {
            return null;
        }

        // Avoid double-wrapping: check if this is already our proxy
        if (Proxy.isProxyClass(stmt.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(stmt);
            if (handler instanceof PreparedStatementWrapper) {
                AgentConfig.debug("skip wrap - already wrapped: " + stmt.getClass().getName());
                return stmt; // Already our proxy, don't wrap again
            }
        }

        try {
            long wrapNum = AgentConfig.incrementWrap();
            AgentConfig.debug("wrap #" + wrapNum + " PreparedStatement class=" + stmt.getClass().getName());

            Class<?>[] interfaces = getInterfaces(stmt);
            PreparedStatementWrapper handler = new PreparedStatementWrapper(stmt, sql);

            ClassLoader classLoader = stmt.getClass().getClassLoader();
            if (classLoader == null) {
                classLoader = Thread.currentThread().getContextClassLoader();
            }
            if (classLoader == null) {
                classLoader = PreparedStatementWrapper.class.getClassLoader();
            }

            return (PreparedStatement) Proxy.newProxyInstance(
                    classLoader,
                    interfaces,
                    handler);
        } catch (Throwable t) {
            SqlLogWriter.getInstance().writeError("Failed to create proxy for PreparedStatement", t);
            return stmt; // Return original on failure
        }
    }

    /**
     * Creates a proxy wrapper for a CallableStatement.
     * Returns the original statement if wrapping fails.
     */
    public static CallableStatement wrapCallable(CallableStatement stmt, String sql) {
        if (stmt == null) {
            return null;
        }

        try {
            long wrapNum = AgentConfig.incrementWrap();
            AgentConfig.debug("wrap #" + wrapNum + " CallableStatement class=" + stmt.getClass().getName());

            Class<?>[] interfaces = getInterfaces(stmt);
            PreparedStatementWrapper handler = new PreparedStatementWrapper(stmt, sql);

            ClassLoader classLoader = stmt.getClass().getClassLoader();
            if (classLoader == null) {
                classLoader = Thread.currentThread().getContextClassLoader();
            }
            if (classLoader == null) {
                classLoader = PreparedStatementWrapper.class.getClassLoader();
            }

            return (CallableStatement) Proxy.newProxyInstance(
                    classLoader,
                    interfaces,
                    handler);
        } catch (Throwable t) {
            SqlLogWriter.getInstance().writeError("Failed to create proxy for CallableStatement", t);
            return stmt; // Return original on failure
        }
    }

    private static Class<?>[] getInterfaces(Object obj) {
        List<Class<?>> interfaces = new ArrayList<Class<?>>();

        try {
            Class<?> current = obj.getClass();

            while (current != null) {
                try {
                    for (Class<?> iface : current.getInterfaces()) {
                        if (!interfaces.contains(iface)) {
                            interfaces.add(iface);
                        }
                    }
                } catch (Throwable t) {
                    // Some classloaders may throw on getInterfaces()
                    SqlLogWriter.getInstance().writeError("Error getting interfaces for " + current.getName(), t);
                }
                current = current.getSuperclass();
            }
        } catch (Throwable t) {
            SqlLogWriter.getInstance().writeError("Error traversing class hierarchy", t);
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
