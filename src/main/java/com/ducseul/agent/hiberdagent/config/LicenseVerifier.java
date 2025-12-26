package com.ducseul.agent.hiberdagent.config;

import com.ducseul.agent.hiberdagent.entity.ParsedLicense;
import com.ducseul.agent.hiberdagent.entity.VerificationResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Verifies licenses using a public key.
 *
 * @author bug
 */
public class LicenseVerifier {

    private static final String ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final PublicKey publicKey;

    /**
     * Reads all content from a file as a String (Java 8 compatible).
     *
     * @param path the path to the file
     * @return the file content as a String
     * @throws IOException if the file cannot be read
     */
    private static String readFileContent(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Repeats a string n times (Java 8 compatible).
     *
     * @param str the string to repeat
     * @param count the number of times to repeat
     * @return the repeated string
     */
    private static String repeatString(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    /**
     * Creates a LicenseVerifier with the specified public key.
     *
     * @param publicKey the public key for verifying licenses
     */
    public LicenseVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    /**
     * Gets all MAC addresses of the current machine.
     *
     * @return list of MAC addresses in uppercase format (XX:XX:XX:XX:XX:XX)
     */
    public static List<String> getMacAddresses() {
        List<String> macAddresses = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i < mac.length - 1) {
                            sb.append(":");
                        }
                    }
                    macAddresses.add(sb.toString());
                }
            }
        } catch (SocketException e) {
            // Ignore and return empty list
        }
        return macAddresses;
    }

    /**
     * Normalizes a MAC address to uppercase with colon separators.
     *
     * @param macAddress the MAC address to normalize
     * @return normalized MAC address (XX:XX:XX:XX:XX:XX)
     */
    private static String normalizeMacAddress(String macAddress) {
        if (macAddress == null) return null;
        return macAddress.toUpperCase().replace("-", ":");
    }

    /**
     * Checks if the license MAC address matches any of the machine's MAC addresses.
     *
     * @param licenseMac the MAC address from the license
     * @return true if matched or no MAC restriction, false otherwise
     */
    public static boolean isMacAddressValid(String licenseMac) {
        if (licenseMac == null || licenseMac.isEmpty()) {
            return true; // No MAC restriction
        }
        String normalizedLicenseMac = normalizeMacAddress(licenseMac);
        List<String> macAddresses = getMacAddresses();
        for (String mac : macAddresses) {
            if (mac.equals(normalizedLicenseMac)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads a public key from a PEM file.
     *
     * @param pemFilePath path to the PEM file containing the public key
     * @return the loaded PublicKey
     * @throws IOException if the file cannot be read
     * @throws NoSuchAlgorithmException if RSA algorithm is not available
     * @throws InvalidKeySpecException if the key specification is invalid
     */
    public static PublicKey loadPublicKeyFromPEM(Path pemFilePath)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String pemContent = readFileContent(pemFilePath);

        // Remove PEM headers and whitespace
        String base64Key = pemContent
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
        return keyFactory.generatePublic(keySpec);
    }

    /**
     * Parses a license and extracts data and signature.
     * Format: {base64-metadata}.{signature}
     *
     * @param licenseContent the license content as a string
     * @return a ParsedLicense containing data and signature
     */
    public ParsedLicense parseLicense(String licenseContent) {
        // Remove any whitespace
        String cleanContent = licenseContent.trim().replaceAll("\\s", "");

        // Split by dot separator
        int dotIndex = cleanContent.lastIndexOf('.');
        if (dotIndex == -1) {
            throw new IllegalArgumentException("Invalid license format: missing separator");
        }

        String metadataBase64 = cleanContent.substring(0, dotIndex);
        String signatureBase64 = cleanContent.substring(dotIndex + 1);

        // Decode metadata from Base64
        String licenseData = new String(Base64.getDecoder().decode(metadataBase64));

        // Parse license fields
        Map<String, String> fields = new HashMap<>();
        for (String line : licenseData.split("\n")) {
            int eqIndex = line.indexOf('=');
            if (eqIndex > 0) {
                String key = line.substring(0, eqIndex).trim();
                String value = line.substring(eqIndex + 1).trim();
                fields.put(key, value);
            }
        }

        return new ParsedLicense(licenseData, signatureBase64, fields);
    }

    /**
     * Verifies a license signature.
     *
     * @param licenseData the original license data
     * @param signatureBase64 the Base64-encoded signature
     * @return true if the signature is valid, false otherwise
     * @throws NoSuchAlgorithmException if the signature algorithm is not available
     * @throws InvalidKeyException if the public key is invalid
     * @throws SignatureException if verification fails
     */
    public boolean verifySignature(String licenseData, String signatureBase64)
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);

        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initVerify(publicKey);
        signature.update(licenseData.getBytes());

        return signature.verify(signatureBytes);
    }

    /**
     * Verifies a license from a string and returns the result.
     *
     * @param licenseContent the license content as a string
     * @return VerificationResult containing status and license info
     */
    public VerificationResult verifyLicense(String licenseContent) {
        try {
            ParsedLicense parsed = parseLicense(licenseContent);
            boolean isValid = verifySignature(parsed.licenseData, parsed.signatureBase64);

            if (!isValid) {
                return new VerificationResult(false, "Invalid signature", null);
            }

            // Check expiration
            String validToStr = parsed.fields.get("ValidTo");
            if (validToStr != null) {
                LocalDate validTo = LocalDate.parse(validToStr, DATE_FORMAT);
                if (LocalDate.now().isAfter(validTo)) {
                    return new VerificationResult(false, "License expired on " + validToStr, parsed.fields);
                }
            }

            // Check MAC address (machine lock)
            String licenseMac = parsed.fields.get("MacAddress");
            if (!isMacAddressValid(licenseMac)) {
                return new VerificationResult(false, "License is locked to a different machine (MAC: " + licenseMac + ")", parsed.fields);
            }

            return new VerificationResult(true, "License is valid", parsed.fields);

        } catch (Exception e) {
            return new VerificationResult(false, "Verification error: " + e.getMessage(), null);
        }
    }

    /**
     * Verifies a license from a file (.lic) and returns the result.
     *
     * @param licenseFilePath path to the license file
     * @return VerificationResult containing status and license info
     * @throws IOException if the file cannot be read
     */
    public VerificationResult verifyLicenseFromFile(Path licenseFilePath) throws IOException {
        String licenseContent = readFileContent(licenseFilePath);
        return verifyLicense(licenseContent);
    }

    /**
     * Verifies a license from a file (.lic) and returns the result.
     *
     * @param licenseFilePath path to the license file as a string
     * @return VerificationResult containing status and license info
     * @throws IOException if the file cannot be read
     */
    public VerificationResult verifyLicenseFromFile(String licenseFilePath) throws IOException {
        return verifyLicenseFromFile(new File(licenseFilePath).getPath());
    }

    /**
     * Parses a single-line license string.
     * Format: {base64-metadata}.{signature}
     * This is the same as parseLicense since both formats are now identical.
     *
     * @param licenseString the license string
     * @return a ParsedLicense containing data and signature
     */
    public ParsedLicense parseLicenseString(String licenseString) {
        return parseLicense(licenseString);
    }

    /**
     * Verifies a license string and returns the result.
     * Format: {base64-metadata}.{signature}
     * This is the same as verifyLicense since both formats are now identical.
     *
     * @param licenseString the license string
     * @return VerificationResult containing status and license info
     */
    public VerificationResult verifyLicenseString(String licenseString) {
        return verifyLicense(licenseString);
    }

    /**
     * Prints license information to console.
     *
     * @param result the verification result
     */
    public void printLicenseInfo(VerificationResult result) {
        System.out.println(repeatString("=", 50));
        System.out.println("LICENSE VERIFICATION RESULT");
        System.out.println(repeatString("=", 50));
        System.out.println("Status: " + (result.isValid ? "VALID" : "INVALID"));
        System.out.println("Message: " + result.message);
        System.out.println(repeatString("-", 50));

        if (result.fields != null && !result.fields.isEmpty()) {
            System.out.println("LICENSE DETAILS:");
            System.out.println(repeatString("-", 50));
            for (Map.Entry<String, String> entry : result.fields.entrySet()) {
                System.out.println("  " + entry.getKey() + ": " + entry.getValue());
            }
        }
        System.out.println(repeatString("=", 50));
    }
}
