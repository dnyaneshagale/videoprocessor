package com.vidprocessor.service;

import org.springframework.stereotype.Service;
import java.util.regex.Pattern;

@Service
public class ValidationService {

    // Updated pattern to allow spaces
    private static final Pattern VALID_R2_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.\\/@ ]+$");

    /**
     * Validates R2 object keys to prevent path traversal and injection attacks
     */
    public void validateR2ObjectKey(String r2ObjectKey) {
        if (r2ObjectKey == null || r2ObjectKey.isEmpty()) {
            throw new IllegalArgumentException("R2 object key cannot be null or empty");
        }

        if (r2ObjectKey.contains("..")) {
            throw new IllegalArgumentException("R2 object key cannot contain path traversal sequences");
        }

        if (!VALID_R2_KEY_PATTERN.matcher(r2ObjectKey).matches()) {
            throw new IllegalArgumentException("R2 object key contains invalid characters");
        }

        // Additional validations
        if (r2ObjectKey.length() > 1024) {
            throw new IllegalArgumentException("R2 object key exceeds maximum length");
        }
    }
}