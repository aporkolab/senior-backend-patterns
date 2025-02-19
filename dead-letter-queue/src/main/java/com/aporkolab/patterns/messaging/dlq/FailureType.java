package com.aporkolab.patterns.messaging.dlq;

/**
 * Categorizes why a message was sent to the Dead Letter Queue.
 */
public enum FailureType {
    
    /** Business logic validation failed */
    VALIDATION_ERROR,
    
    /** Message format/parsing failed */
    DESERIALIZATION_ERROR,
    
    /** Exceeded retry limit for transient errors */
    MAX_RETRIES_EXCEEDED,
    
    /** Explicitly marked as non-retryable */
    PERMANENT,
    
    /** Reprocessing from DLQ failed */
    REPROCESS_FAILED,
    
    /** Unknown/unexpected error */
    UNKNOWN
}
