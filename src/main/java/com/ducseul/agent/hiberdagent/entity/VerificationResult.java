package com.ducseul.agent.hiberdagent.entity;

import java.util.Map;

/**
 * Holds verification result.
 */
public class VerificationResult {
    public final boolean isValid;
    public final String message;
    public final Map<String, String> fields;

    public VerificationResult(boolean isValid, String message, Map<String, String> fields) {
        this.isValid = isValid;
        this.message = message;
        this.fields = fields;
    }
}