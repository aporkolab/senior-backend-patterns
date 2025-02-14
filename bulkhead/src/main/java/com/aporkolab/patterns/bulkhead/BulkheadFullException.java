package com.aporkolab.patterns.bulkhead;

/**
 * Exception thrown when a bulkhead rejects a call due to capacity limits.
 */
public class BulkheadFullException extends RuntimeException {

    private final String bulkheadName;
    private final int maxConcurrentCalls;

    public BulkheadFullException(String bulkheadName, int maxConcurrentCalls) {
        super(String.format("Bulkhead '%s' is full (max: %d concurrent calls)", 
                bulkheadName, maxConcurrentCalls));
        this.bulkheadName = bulkheadName;
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    public String getBulkheadName() {
        return bulkheadName;
    }

    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }
}
