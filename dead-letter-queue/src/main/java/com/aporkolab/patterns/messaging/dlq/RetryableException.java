package com.aporkolab.patterns.messaging.dlq;

/**
 * Thrown to signal that message processing should be retried.
 * The Kafka consumer error handler should NOT commit offset when this is thrown.
 */
public class RetryableException extends RuntimeException {

    public RetryableException(String message) {
        super(message);
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
