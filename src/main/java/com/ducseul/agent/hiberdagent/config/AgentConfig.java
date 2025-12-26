package com.ducseul.agent.hiberdagent.config;

import com.ducseul.agent.hiberdagent.entity.VerificationResult;
import com.ducseul.agent.hiberdagent.log.SqlLogWriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuration holder for the SQL logging agent.
 * Reads from system properties at agent startup.
 */
public final class AgentConfig {

    private static final String PREFIX = "hibernate.agent.";

    private static final String PUBLIC_KEY_RESOURCE = "/public_key.pem";

    private static final long slowThresholdMs;
    private static final boolean logStack;
    private static final boolean logSqlAlways;
    private static final int maxStackDepth;
    private static final boolean debug;
    private static final String[] stackPackageFilters;
    private static final VerificationResult licenseResult;

    // Counters for diagnostics
    private static final AtomicLong invokeCount = new AtomicLong(0);
    private static final AtomicLong executeCount = new AtomicLong(0);
    private static final AtomicLong wrapCount = new AtomicLong(0);
    private static final AtomicLong queryIdCounter = new AtomicLong(0);

    static {
        // Verify license first - stop application if invalid
        licenseResult = verifyLicenseAtStartup();
        if (!licenseResult.isValid) {
            System.err.println();
            System.err.println("================================================================================");
            System.err.println("                         HIBERDAGENT LICENSE ERROR");
            System.err.println("================================================================================");
            System.err.println();
            System.err.println("  License verification failed: " + licenseResult.message);
            System.err.println();
            System.err.println("  Please provide a valid license using one of these methods:");
            System.err.println();
            System.err.println("  1. Pass license file path:");
            System.err.println("     -Dhibernate.agent.license=/path/to/license.lic");
            System.err.println();
            System.err.println("  2. Pass license string directly:");
            System.err.println("     -Dhibernate.agent.license=license-string");
            System.err.println();
            System.err.println("  Contact your administrator to obtain a valid license.");
            System.err.println();
            System.err.println("================================================================================");
            System.err.println();
            throw new RuntimeException("HiberDAgent license verification failed: " + licenseResult.message);
        }

        slowThresholdMs = getLong(PREFIX + "slowThresholdMs", 5000L);
        logStack = getBoolean(PREFIX + "logStack", false);
        logSqlAlways = getBoolean(PREFIX + "logSqlAlways", true);
        maxStackDepth = getInt(PREFIX + "maxStackDepth", 10);
        debug = getBoolean(PREFIX + "debug", false);
        stackPackageFilters = parsePackageFilters(System.getProperty(PREFIX + "stackPackageFilter"));
    }

    /**
     * Verifies the license at startup.
     *
     * @return the verification result
     */
    private static VerificationResult verifyLicenseAtStartup() {
        String licenseValue = System.getProperty(PREFIX + "license");

        if (licenseValue == null || licenseValue.trim().isEmpty()) {
            return new VerificationResult(false, "No license provided", null);
        }

        licenseValue = licenseValue.trim();

        try {
            // Load public key from resources
            PublicKey publicKey = loadPublicKeyFromResources();
            if (publicKey == null) {
                return new VerificationResult(false, "Failed to load public key from resources", null);
            }

            LicenseVerifier verifier = new LicenseVerifier(publicKey);

            // Check if it's a file path or license string
            File licenseFile = new File(licenseValue);
            if (licenseFile.exists() && licenseFile.isFile()) {
                // It's a file path
                return verifier.verifyLicenseFromFile(licenseFile.toPath());
            } else {
                // It's a license string
                return verifier.verifyLicense(licenseValue);
            }
        } catch (Exception e) {
            return new VerificationResult(false, "License verification error: " + e.getMessage(), null);
        }
    }

    /**
     * Loads the public key from the embedded resource.
     *
     * @return the public key, or null if loading fails
     */
    private static PublicKey loadPublicKeyFromResources() {
        try (InputStream is = AgentConfig.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (is == null) {
                System.err.println("[HiberDAgent] Public key resource not found: " + PUBLIC_KEY_RESOURCE);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }

            String pemContent = sb.toString();

            // Remove PEM headers and whitespace
            String base64Key = pemContent
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            System.err.println("[HiberDAgent] Failed to load public key: " + e.getMessage());
            return null;
        }
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

    public static VerificationResult getLicenseResult() {
        return licenseResult;
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
        logger.writeLine("  license            = " + (licenseResult.isValid ? "VALID" : "INVALID"));
        if (licenseResult.fields != null) {
            String licensee = licenseResult.fields.get("Licensee");
            String validTo = licenseResult.fields.get("ValidTo");
            if (licensee != null) {
                logger.writeLine("  licensee           = " + licensee);
            }
            if (validTo != null) {
                logger.writeLine("  validTo            = " + validTo);
            }
        }
        logger.writeLine("  slowThresholdMs    = " + slowThresholdMs);
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
