package com.ducseul.agent.hiberdagent.config;

import com.ducseul.agent.hiberdagent.log.SqlLogWriter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuration holder for the SQL logging agent.
 * Reads from system properties at agent startup.
 */
public final class AgentConfig {

    private static final String PREFIX = "hibernate.agent.";

    private static final long slowThresholdMs;
    private static final boolean logStack;
    private static final boolean logSqlAlways;
    private static final int maxStackDepth;
    private static final boolean debug;
    private static final String[] stackPackageFilters;

    // Counters for diagnostics
    private static final AtomicLong invokeCount = new AtomicLong(0);
    private static final AtomicLong executeCount = new AtomicLong(0);
    private static final AtomicLong wrapCount = new AtomicLong(0);
    private static final AtomicLong queryIdCounter = new AtomicLong(0);

    static {
        slowThresholdMs = getLong(PREFIX + "slowThresholdMs", 5000L);
        logStack = getBoolean(PREFIX + "logStack", false);
        logSqlAlways = getBoolean(PREFIX + "logSqlAlways", true);
        maxStackDepth = getInt(PREFIX + "maxStackDepth", 10);
        debug = getBoolean(PREFIX + "debug", false);
        stackPackageFilters = parsePackageFilters(System.getProperty(PREFIX + "stackPackageFilter"));
    }

    private AgentConfig() {
    }

    public static long getSlowThresholdMs() {
        return slowThresholdMs;
    }

    public static boolean isLogStack() {
        return logStack;
    }

    public static boolean isLogSqlAlways() {
        return logSqlAlways;
    }

    public static int getMaxStackDepth() {
        return maxStackDepth;
    }

    public static String[] getStackPackageFilters() {
        return stackPackageFilters;
    }

    public static boolean shouldLog(long executionTimeMs) {
        return logSqlAlways || executionTimeMs >= slowThresholdMs;
    }

    /**
     * Generate a unique query ID for correlating SQL with stack traces.
     */
    public static String generateQueryId() {
        long id = queryIdCounter.incrementAndGet();
        return String.format("Q%06d", id);
    }

    public static boolean isDebug() {
        return debug;
    }

    /**
     * Log debug message directly to stderr (bypasses file writer).
     */
    private static final SimpleDateFormat DEBUG_DATE_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

    public static void debug(String message) {
        if (debug) {
            try {
                String timestamp = DEBUG_DATE_FORMAT.format(new Date());
                System.err.println("[" + timestamp + "] [HiberDAgent-DEBUG] " + message);
                System.err.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Increment and return invoke counter.
     */
    public static long incrementInvoke() {
        return invokeCount.incrementAndGet();
    }

    /**
     * Increment and return execute counter.
     */
    public static long incrementExecute() {
        return executeCount.incrementAndGet();
    }

    /**
     * Increment and return wrap counter.
     */
    public static long incrementWrap() {
        return wrapCount.incrementAndGet();
    }

    /**
     * Get diagnostic counters as string.
     */
    public static String getCounters() {
        return "invokes=" + invokeCount.get() + ", executes=" + executeCount.get() + ", wraps=" + wrapCount.get();
    }

    private static long getLong(String key, long defaultValue) {
        String val = System.getProperty(key);
        if (val != null) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static int getInt(String key, int defaultValue) {
        String val = System.getProperty(key);
        if (val != null) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        String val = System.getProperty(key);
        if (val != null) {
            return Boolean.parseBoolean(val.trim());
        }
        return defaultValue;
    }

    private static String[] parsePackageFilters(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = value.split(",");
        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parts[i].trim();
        }
        return result;
    }

    public static void printConfig() {
        SqlLogWriter logger = SqlLogWriter.getInstance();
        logger.writeLine("[HiberDAgent] Configuration:");
        logger.writeLine("  slowThresholdMs = " + slowThresholdMs);
        logger.writeLine("  logSqlAlways       = " + logSqlAlways);
        logger.writeLine("  logStack           = " + logStack);
        logger.writeLine("  maxStackDepth      = " + maxStackDepth);
        logger.writeLine("  stackPackageFilter = "
                + (stackPackageFilters.length == 0 ? "(all)" : String.join(", ", stackPackageFilters)));
        logger.writeLine("  debug              = " + debug);
        logger.writeLine(
                "  stdout             = " + (logger.isFileMode() ? logger.getFilePath() + " (file)" : "console"));

        if (debug) {
            System.err.println("[HiberDAgent-DEBUG] Debug mode enabled - verbose logging to stderr");
        }
    }

    // Print counters on shutdown
    static {
        Runtime.getRuntime().addShutdownHook(new Thread("HiberDAgent-CounterDump") {
            @Override
            public void run() {
                try {
                    System.err.println("[HiberDAgent] Shutdown counters: " + getCounters());
                    System.err.flush();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
