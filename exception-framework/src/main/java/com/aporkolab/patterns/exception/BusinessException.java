package com.aporkolab.patterns.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for business/client errors (4xx).
 * 
 * Use for errors caused by client input or business rule violations.
 * Safe to expose details to clients.
 */
public abstract class BusinessException extends DomainException {

    protected BusinessException(String code, String message, HttpStatus httpStatus) {
        super(code, message, httpStatus);
    }

    protected BusinessException(String code, String message, HttpStatus httpStatus, Throwable cause) {
        super(code, message, httpStatus, cause);
    }
}
