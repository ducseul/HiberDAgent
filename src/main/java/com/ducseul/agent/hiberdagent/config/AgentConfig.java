package com.ducseul.agent.hiberdagent.config;

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

    static {
        slowThresholdMs = getLong(PREFIX + "slowThresholdMs", 5000L);
        logStack = getBoolean(PREFIX + "logStack", false);
        logSqlAlways = getBoolean(PREFIX + "logSqlAlways", true);
        maxStackDepth = getInt(PREFIX + "maxStackDepth", 10);
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

    public static boolean shouldLog(long executionTimeMs) {
        return logSqlAlways || executionTimeMs >= slowThresholdMs;
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

    public static void printConfig() {
        System.out.println("[HiberDAgent] Configuration:");
        System.out.println("  slowThresholdMs = " + slowThresholdMs);
        System.out.println("  logSqlAlways    = " + logSqlAlways);
        System.out.println("  logStack        = " + logStack);
        System.out.println("  maxStackDepth   = " + maxStackDepth);
    }
}
