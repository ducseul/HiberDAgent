package com.ducseul.agent.hiberdagent.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Runtime integrity checker to detect JAR tampering.
 * Uses SHA-256 and MD5 checksums to verify class files haven't been modified.
 */
public final class IntegrityChecker {

    private static final String EXPECTED_HASH_RESOURCE = "/integrity.hash";
    private static volatile boolean verified = false;
    private static volatile String cachedHash = null;

    private IntegrityChecker() {
    }

    /**
     * Verifies the integrity of the agent JAR at runtime.
     * Should be called early during agent initialization.
     *
     * @throws SecurityException if tampering is detected
     */
    public static void verify() throws SecurityException {
        if (verified) {
            return;
        }

        synchronized (IntegrityChecker.class) {
            if (verified) {
                return;
            }

            try {
                String expectedHash = loadExpectedHash();
                if (expectedHash == null || expectedHash.isEmpty()) {
                    // No hash embedded - skip verification (development mode)
                    verified = true;
                    return;
                }

                String actualHash = computeJarHash();
                if (actualHash == null) {
                    // Cannot compute hash - running from classes, not JAR
                    verified = true;
                    return;
                }

                cachedHash = actualHash;

                if (!constantTimeEquals(expectedHash.trim(), actualHash.trim())) {
                    throw new SecurityException("JAR integrity check failed - tampering detected");
                }

                verified = true;

            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                throw new SecurityException("Integrity verification failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Loads the expected hash from embedded resource.
     */
    private static String loadExpectedHash() {
        try (InputStream is = IntegrityChecker.class.getResourceAsStream(EXPECTED_HASH_RESOURCE)) {
            if (is == null) {
                return null;
            }
            byte[] bytes = readAllBytes(is);
            return new String(bytes, "UTF-8").trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Computes SHA-256 hash of all class files in the JAR.
     */
    private static String computeJarHash() {
        try {
            URL location = IntegrityChecker.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }

            String path = location.getPath();
            // Handle Windows paths
            if (path.startsWith("/") && path.length() > 2 && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            // URL decode
            path = java.net.URLDecoder.decode(path, "UTF-8");

            if (!path.endsWith(".jar")) {
                return null;
            }

            return computeJarClassesHash(path);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Computes combined hash of all .class files in JAR.
     */
    private static String computeJarClassesHash(String jarPath) throws IOException, NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        MessageDigest md5 = MessageDigest.getInstance("MD5");

        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // Only hash our package's class files
                if (name.startsWith("com/ducseul/agent/") && name.endsWith(".class")) {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        byte[] bytes = readAllBytes(is);
                        sha256.update(bytes);
                        md5.update(bytes);
                    }
                }
            }
        }

        // Combine both hashes for stronger verification
        byte[] sha256Hash = sha256.digest();
        byte[] md5Hash = md5.digest();

        return bytesToHex(sha256Hash) + ":" + bytesToHex(md5Hash);
    }

    /**
     * Computes hash of agent classes for embedding during build.
     * Called by build process to generate the expected hash.
     */
    public static String computeHashForBuild(String jarPath) {
        try {
            return computeJarClassesHash(jarPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute hash: " + e.getMessage(), e);
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Returns the computed hash (for debugging/logging).
     */
    public static String getComputedHash() {
        return cachedHash;
    }

    /**
     * Checks if verification has been performed.
     */
    public static boolean isVerified() {
        return verified;
    }
}
