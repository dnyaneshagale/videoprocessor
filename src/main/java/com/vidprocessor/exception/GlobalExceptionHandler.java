package com.vidprocessor.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for the application
 * Provides consistent error responses across all endpoints
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        log.warn("Validation error: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Validation Error",
                ex.getMessage(),
                request.getDescription(false)
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDeniedException(
            AccessDeniedException ex, WebRequest request) {
        log.warn("Access denied: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.FORBIDDEN,
                "Access Denied",
                "You don't have permission to access this resource",
                request.getDescription(false)
        );
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointerException(
            NullPointerException ex, WebRequest request) {
        log.error("Null pointer exception occurred", ex);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Error",
                "An unexpected error occurred. Please try again later.",
                request.getDescription(false)
        );
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(
            RuntimeException ex, WebRequest request) {
        log.error("Runtime exception occurred: {}", ex.getMessage(), ex);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Error",
                "An error occurred while processing your request",
                request.getDescription(false)
        );
    }

    @ExceptionHandler(NoSuchKeyException.class)
    public ResponseEntity<Map<String, Object>> handleNoSuchKeyException(
            NoSuchKeyException ex, WebRequest request) {
        log.error("File not found in R2 storage", ex);
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "File Not Found",
                "The requested file does not exist in storage. Please ensure the file is uploaded to R2 before processing.",
                request.getDescription(false)
        );
    }

    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<Map<String, Object>> handleS3Exception(
            S3Exception ex, WebRequest request) {
        log.error("R2 storage error: {}", ex.getMessage(), ex);
        String errorMsg = ex.awsErrorDetails() != null 
            ? ex.awsErrorDetails().errorMessage() 
            : ex.getMessage();
        return buildErrorResponse(
                HttpStatus.BAD_GATEWAY,
                "Storage Service Error",
                "R2 storage error: " + errorMsg,
                request.getDescription(false)
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGlobalException(
            Exception ex, WebRequest request) {
        log.error("Unexpected exception occurred: {}", ex.getMessage(), ex);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred. Please contact support if the problem persists.",
                request.getDescription(false)
        );
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status,
            String error,
            String message,
            String path) {
        
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now().toString());
        errorResponse.put("status", status.value());
        errorResponse.put("error", error);
        errorResponse.put("message", message);
        errorResponse.put("path", path.replace("uri=", ""));

        return new ResponseEntity<>(errorResponse, status);
    }
}
