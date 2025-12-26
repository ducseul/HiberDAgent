package com.ducseul.agent.hiberdagent.entity;

import java.util.Map;

/**
 * Holds parsed license data.
 */
public class ParsedLicense {
    public final String licenseData;
    public final String signatureBase64;
    public final Map<String, String> fields;

    public ParsedLicense(String licenseData, String signatureBase64, Map<String, String> fields) {
        this.licenseData = licenseData;
        this.signatureBase64 = signatureBase64;
        this.fields = fields;
    }
}
