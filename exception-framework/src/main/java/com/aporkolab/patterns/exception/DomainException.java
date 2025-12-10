package com.aporkolab.patterns.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Base class for all domain exceptions.
 * 
 * Provides:
 * - Error code for client handling
 * - HTTP status mapping
 * - Structured context for debugging
 * - Timestamp for correlation
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;
    private final Map<String, Object> context;
    private final Instant timestamp;

    protected DomainException(String code, String message, HttpStatus httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.context = new HashMap<>();
        this.timestamp = Instant.now();
    }

    protected DomainException(String code, String message, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.context = new HashMap<>();
        this.timestamp = Instant.now();
    }

    /**
     * Add contextual information for debugging.
     * Fluent API for chaining.
     */
    public DomainException with(String key, Object value) {
        this.context.put(key, value);
        return this;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public Map<String, Object> getContext() {
        return Map.copyOf(context);
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
