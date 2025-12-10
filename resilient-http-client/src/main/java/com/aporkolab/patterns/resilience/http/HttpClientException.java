package com.aporkolab.patterns.resilience.http;

/**
 * Thrown when all retry attempts are exhausted or a non-retryable error occurs.
 */
public class HttpClientException extends RuntimeException {

    public HttpClientException(String message) {
        super(message);
    }

    public HttpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
