package com.aporkolab.patterns.exception;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Validation errors - invalid input data.
 */
public class ValidationException extends BusinessException {

    public ValidationException(String field, String message) {
        super("VALIDATION_ERROR", String.format("Validation failed for '%s': %s", field, message), HttpStatus.BAD_REQUEST);
        with("field", field);
    }

    public ValidationException(List<FieldError> errors) {
        super("VALIDATION_ERROR", "Validation failed for multiple fields", HttpStatus.BAD_REQUEST);
        with("errors", errors);
    }

    public record FieldError(String field, String message) {}
}
