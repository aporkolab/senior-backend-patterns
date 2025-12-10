package com.aporkolab.patterns.resilience.circuitbreaker;

/**
 * Thrown when attempting to execute through an open circuit breaker.
 */
public class CircuitBreakerOpenException extends RuntimeException {

    private final String circuitBreakerName;

    public CircuitBreakerOpenException(String circuitBreakerName) {
        super(String.format("Circuit breaker '%s' is OPEN - request rejected", circuitBreakerName));
        this.circuitBreakerName = circuitBreakerName;
    }

    public String getCircuitBreakerName() {
        return circuitBreakerName;
    }
}
