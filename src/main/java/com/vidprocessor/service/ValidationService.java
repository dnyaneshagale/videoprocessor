package com.vidprocessor.service;

import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;
import java.util.regex.Pattern;
import java.util.Set;

@Service
public class ValidationService {

    // Updated pattern to allow spaces and more file extensions
    private static final Pattern VALID_R2_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.\\/@ ]+$");

    // Set of supported video format extensions
    private static final Set<String> SUPPORTED_VIDEO_FORMATS = Set.of(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "mts",
            "m2ts", "mpg", "mpeg", "vob", "ogv", "mxf", "f4v", "asf", "divx"
    );

    /**
     * Validates R2 object keys to prevent path traversal and injection attacks
     */
    public void validateR2ObjectKey(String r2ObjectKey) {
        if (r2ObjectKey == null || r2ObjectKey.trim().isEmpty()) {
            throw new IllegalArgumentException("R2 object key cannot be null or empty");
        }

        // Trim the key
        r2ObjectKey = r2ObjectKey.trim();

        if (r2ObjectKey.contains("..")) {
            throw new IllegalArgumentException("R2 object key cannot contain path traversal sequences");
        }

        if (!VALID_R2_KEY_PATTERN.matcher(r2ObjectKey).matches()) {
            throw new IllegalArgumentException("R2 object key contains invalid characters");
        }

        // Additional validations
        if (r2ObjectKey.length() > 1024) {
            throw new IllegalArgumentException("R2 object key exceeds maximum length (1024 characters)");
        }

        // Check file extension is a supported video format
        String extension = FilenameUtils.getExtension(r2ObjectKey).toLowerCase();
        if (extension.isEmpty()) {
            throw new IllegalArgumentException("File must have a valid extension. Supported formats: " + 
                    String.join(", ", SUPPORTED_VIDEO_FORMATS));
        }
        
        if (!SUPPORTED_VIDEO_FORMATS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported video format: '" + extension +
                    "'. Supported formats: " + String.join(", ", SUPPORTED_VIDEO_FORMATS));
        }
    }

}