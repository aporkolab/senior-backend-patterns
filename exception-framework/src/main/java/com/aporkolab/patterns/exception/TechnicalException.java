package com.aporkolab.patterns.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for technical/server errors (5xx).
 * 
 * Use for infrastructure failures, integration errors, unexpected states.
 * Do NOT expose details to clients - log server-side.
 */
public abstract class TechnicalException extends DomainException {

    protected TechnicalException(String code, String message) {
        super(code, message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    protected TechnicalException(String code, String message, Throwable cause) {
        super(code, message, HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }

    protected TechnicalException(String code, String message, HttpStatus httpStatus, Throwable cause) {
        super(code, message, httpStatus, cause);
    }
}
