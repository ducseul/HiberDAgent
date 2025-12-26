package com.ducseul.agent.hiberdagent.wrapper;

import com.ducseul.agent.hiberdagent.config.AgentConfig;
import com.ducseul.agent.hiberdagent.format.SqlFormatter;
import com.ducseul.agent.hiberdagent.log.SqlLogWriter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * InvocationHandler that wraps plain Statement to log SQL statements
 * when execute methods are called with SQL strings.
 */
public class StatementWrapper implements InvocationHandler {

    private final Statement delegate;

    private static final String[] EXECUTE_WITH_SQL_METHODS = {
        "execute", "executeQuery", "executeUpdate", "executeLargeUpdate"
    };

    public StatementWrapper(Statement delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        try {
            // Handle execute methods that take SQL string as first argument
            if (isExecuteWithSqlMethod(methodName) && args != null && args.length > 0 && args[0] instanceof String) {
                return handleExecuteWithSql(method, args, (String) args[0]);
            }

            // Handle addBatch(String sql)
            if ("addBatch".equals(methodName) && args != null && args.length > 0 && args[0] instanceof String) {
                // Just delegate, we'll log on executeBatch
                return method.invoke(delegate, args);
            }

            // Handle executeBatch
            if ("executeBatch".equals(methodName) || "executeLargeBatch".equals(methodName)) {
                return handleExecuteBatch(method, args);
            }

            // Default: delegate
            return method.invoke(delegate, args);

        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        } catch (Throwable t) {
            // Log any unexpected errors in the agent itself
            SqlLogWriter.getInstance().writeError(
                "Unexpected error in StatementWrapper.invoke() for method '" + methodName + "'", t);
            // Re-throw to not silently swallow errors
            throw t;
        }
    }

    private boolean isExecuteWithSqlMethod(String methodName) {
        for (String execMethod : EXECUTE_WITH_SQL_METHODS) {
            if (methodName.equals(execMethod)) {
                return true;
            }
        }
        return false;
    }

    private Object handleExecuteWithSql(Method method, Object[] args, String sql) throws Throwable {
        long startTime = System.currentTimeMillis();
        try {
            return method.invoke(delegate, args);
        } finally {
            try {
                long elapsed = System.currentTimeMillis() - startTime;
                logIfNeeded(elapsed, sql);
            } catch (Throwable t) {
                SqlLogWriter.getInstance().writeError("Error in handleExecuteWithSql logging", t);
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
                    logger.writeLine("[SQL] (took=" + elapsed + "ms) [BATCH execution via plain Statement]");
                    if (AgentConfig.isLogStack()) {
                        String stack = SqlFormatter.formatStackTrace(
                                AgentConfig.getMaxStackDepth(),
                                AgentConfig.isCompactStackTrace());
                        if (!stack.isEmpty()) {
                            logger.writeLine("[STACK] " + stack);
                        }
                    }
                }
            } catch (Throwable t) {
                SqlLogWriter.getInstance().writeError("Error in handleExecuteBatch logging", t);
            }
        }
    }

    private void logIfNeeded(long elapsed, String sql) {
        if (AgentConfig.shouldLog(elapsed)) {
            SqlLogWriter logger = SqlLogWriter.getInstance();
            logger.writeLine("[SQL] (took=" + elapsed + "ms) " + sql);

            if (AgentConfig.isLogStack()) {
                String stack = SqlFormatter.formatStackTrace(
                        AgentConfig.getMaxStackDepth(),
                        AgentConfig.isCompactStackTrace());
                if (!stack.isEmpty()) {
                    logger.writeLine("[STACK] " + stack);
                }
            }
        }
    }

    /**
     * Creates a proxy wrapper for a Statement.
     * Returns the original statement if wrapping fails.
     */
    public static Statement wrap(Statement stmt) {
        if (stmt == null) {
            return null;
        }

        try {
            Class<?>[] interfaces = getInterfaces(stmt);
            StatementWrapper handler = new StatementWrapper(stmt);

            ClassLoader classLoader = stmt.getClass().getClassLoader();
            if (classLoader == null) {
                classLoader = Thread.currentThread().getContextClassLoader();
            }
            if (classLoader == null) {
                classLoader = StatementWrapper.class.getClassLoader();
            }

            return (Statement) Proxy.newProxyInstance(
                classLoader,
                interfaces,
                handler
            );
        } catch (Throwable t) {
            SqlLogWriter.getInstance().writeError("Failed to create proxy for Statement", t);
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
                    SqlLogWriter.getInstance().writeError("Error getting interfaces for " + current.getName(), t);
                }
                current = current.getSuperclass();
            }
        } catch (Throwable t) {
            SqlLogWriter.getInstance().writeError("Error traversing class hierarchy", t);
        }

        if (!interfaces.contains(Statement.class)) {
            interfaces.add(Statement.class);
        }

        return interfaces.toArray(new Class<?>[0]);
    }
}
