package com.aporkolab.patterns.exception;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for REST APIs.
 * 
 * Translates domain exceptions to consistent HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle business exceptions - safe to expose to clients.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        log.warn("Business error: {} - {}", ex.getCode(), ex.getMessage());

        ErrorResponse response = new ErrorResponse(
                ex.getCode(),
                ex.getMessage(),
                ex.getTimestamp(),
                ex.getContext()
        );

        return ResponseEntity.status(ex.getHttpStatus()).body(response);
    }

    /**
     * Handle technical exceptions - log details, return generic message.
     */
    @ExceptionHandler(TechnicalException.class)
    public ResponseEntity<ErrorResponse> handleTechnicalException(TechnicalException ex) {
        log.error("Technical error: {} - {}", ex.getCode(), ex.getMessage(), ex);

        // Don't expose internal details to clients
        ErrorResponse response = new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.",
                ex.getTimestamp(),
                Map.of()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Catch-all for unexpected exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Unexpected error", ex);

        ErrorResponse response = new ErrorResponse(
                "UNEXPECTED_ERROR",
                "An unexpected error occurred. Please try again later.",
                Instant.now(),
                Map.of()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Standard error response format.
     */
    public record ErrorResponse(
            String code,
            String message,
            Instant timestamp,
            Map<String, Object> details
    ) {
        public static ErrorResponse generic(String message) {
            return new ErrorResponse("ERROR", message, Instant.now(), Map.of());
        }
    }
}
