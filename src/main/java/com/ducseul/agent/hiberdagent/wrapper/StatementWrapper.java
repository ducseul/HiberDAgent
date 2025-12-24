package com.ducseul.agent.hiberdagent.wrapper;

import com.ducseul.agent.hiberdagent.config.AgentConfig;
import com.ducseul.agent.hiberdagent.format.SqlFormatter;

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
            long elapsed = System.currentTimeMillis() - startTime;
            logIfNeeded(elapsed, sql);
        }
    }

    private Object handleExecuteBatch(Method method, Object[] args) throws Throwable {
        long startTime = System.currentTimeMillis();
        try {
            return method.invoke(delegate, args);
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            if (AgentConfig.shouldLog(elapsed)) {
                System.out.println("[SQL] (took=" + elapsed + "ms) [BATCH execution via plain Statement]");
                if (AgentConfig.isLogStack()) {
                    String stack = SqlFormatter.formatStackTrace(AgentConfig.getMaxStackDepth());
                    if (!stack.isEmpty()) {
                        System.out.println("[STACK] " + stack);
                    }
                }
            }
        }
    }

    private void logIfNeeded(long elapsed, String sql) {
        if (AgentConfig.shouldLog(elapsed)) {
            System.out.println("[SQL] (took=" + elapsed + "ms) " + sql);

            if (AgentConfig.isLogStack()) {
                String stack = SqlFormatter.formatStackTrace(AgentConfig.getMaxStackDepth());
                if (!stack.isEmpty()) {
                    System.out.println("[STACK] " + stack);
                }
            }
        }
    }

    /**
     * Creates a proxy wrapper for a Statement.
     */
    public static Statement wrap(Statement stmt) {
        if (stmt == null) {
            return null;
        }

        Class<?>[] interfaces = getInterfaces(stmt);
        StatementWrapper handler = new StatementWrapper(stmt);

        return (Statement) Proxy.newProxyInstance(
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

        if (!interfaces.contains(Statement.class)) {
            interfaces.add(Statement.class);
        }

        return interfaces.toArray(new Class<?>[0]);
    }
}
