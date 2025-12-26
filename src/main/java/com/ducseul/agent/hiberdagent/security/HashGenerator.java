package com.ducseul.agent.hiberdagent.security;

import java.io.*;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.jar.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Build-time utility to generate integrity hash and embed it into the JAR.
 * This is run after ProGuard obfuscation to create the final protected JAR.
 */
public class HashGenerator {

    private static final String HASH_RESOURCE = "integrity.hash";

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: HashGenerator <jar-file>");
            System.exit(1);
        }

        String jarPath = args[0];
        File jarFile = new File(jarPath);

        if (!jarFile.exists()) {
            System.out.println("[HashGenerator] JAR not found (obfuscation may be skipped): " + jarPath);
            System.exit(0);
        }

        try {
            System.out.println("[HashGenerator] Computing integrity hash for: " + jarPath);

            // Compute hash of class files
            String hash = computeHash(jarFile);
            System.out.println("[HashGenerator] Computed hash: " + hash);

            // Embed hash into JAR
            embedHash(jarFile, hash);
            System.out.println("[HashGenerator] Hash embedded successfully");

        } catch (Exception e) {
            System.err.println("[HashGenerator] Failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String computeHash(File jarFile) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        MessageDigest md5 = MessageDigest.getInstance("MD5");

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // Hash our package's class files
                if (name.startsWith("com/ducseul/agent/") && name.endsWith(".class")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        byte[] bytes = readAllBytes(is);
                        sha256.update(bytes);
                        md5.update(bytes);
                    }
                }
            }
        }

        return bytesToHex(sha256.digest()) + ":" + bytesToHex(md5.digest());
    }

    private static void embedHash(File jarFile, String hash) throws Exception {
        File tempFile = new File(jarFile.getParent(), jarFile.getName() + ".tmp");

        try (ZipFile originalJar = new ZipFile(jarFile);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempFile))) {

            // Copy existing entries
            Enumeration<? extends ZipEntry> entries = originalJar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                // Skip if we're replacing it
                if (entry.getName().equals(HASH_RESOURCE)) {
                    continue;
                }

                // Preserve manifest
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    zos.putNextEntry(new ZipEntry(entry.getName()));
                    try (InputStream is = originalJar.getInputStream(entry)) {
                        copy(is, zos);
                    }
                    zos.closeEntry();
                } else {
                    ZipEntry newEntry = new ZipEntry(entry.getName());
                    zos.putNextEntry(newEntry);
                    if (!entry.isDirectory()) {
                        try (InputStream is = originalJar.getInputStream(entry)) {
                            copy(is, zos);
                        }
                    }
                    zos.closeEntry();
                }
            }

            // Add hash file
            zos.putNextEntry(new ZipEntry(HASH_RESOURCE));
            zos.write(hash.getBytes("UTF-8"));
            zos.closeEntry();
        }

        // Replace original with temp
        if (!jarFile.delete()) {
            throw new IOException("Failed to delete original JAR");
        }
        if (!tempFile.renameTo(jarFile)) {
            throw new IOException("Failed to rename temp JAR");
        }
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

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
