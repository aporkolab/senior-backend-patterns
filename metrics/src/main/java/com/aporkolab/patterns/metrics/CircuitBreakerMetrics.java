package com.aporkolab.patterns.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Micrometer metrics wrapper for Circuit Breaker.
 * 
 * Provides the following metrics:
 * - circuit_breaker_calls_total (counter): Total calls by result (success/failure/rejected)
 * - circuit_breaker_state (gauge): Current state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
 * - circuit_breaker_call_duration (timer): Call execution time
 * - circuit_breaker_failure_rate (gauge): Current failure rate percentage
 * - circuit_breaker_state_transitions_total (counter): State transition count
 */
public class CircuitBreakerMetrics {

    private static final String METRIC_PREFIX = "circuit_breaker";

    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry registry;
    private final Tags baseTags;

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter rejectedCounter;
    private final Counter stateTransitionCounter;
    private final Timer callTimer;

    private static final ConcurrentMap<String, CircuitBreakerMetrics> instances = new ConcurrentHashMap<>();

    private CircuitBreakerMetrics(CircuitBreaker circuitBreaker, MeterRegistry registry) {
        this.circuitBreaker = circuitBreaker;
        this.registry = registry;
        this.baseTags = Tags.of("name", circuitBreaker.getName());

        // Counters
        this.successCounter = Counter.builder(METRIC_PREFIX + "_calls_total")
                .description("Total successful circuit breaker calls")
                .tags(baseTags.and("result", "success"))
                .register(registry);

        this.failureCounter = Counter.builder(METRIC_PREFIX + "_calls_total")
                .description("Total failed circuit breaker calls")
                .tags(baseTags.and("result", "failure"))
                .register(registry);

        this.rejectedCounter = Counter.builder(METRIC_PREFIX + "_calls_total")
                .description("Total rejected circuit breaker calls (circuit open)")
                .tags(baseTags.and("result", "rejected"))
                .register(registry);

        this.stateTransitionCounter = Counter.builder(METRIC_PREFIX + "_state_transitions_total")
                .description("Total circuit breaker state transitions")
                .tags(baseTags)
                .register(registry);

        // Timer
        this.callTimer = Timer.builder(METRIC_PREFIX + "_call_duration")
                .description("Circuit breaker call duration")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // Gauges
        Gauge.builder(METRIC_PREFIX + "_state", this, m -> stateToNumber(m.circuitBreaker.getState()))
                .description("Current circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)")
                .tags(baseTags)
                .register(registry);

        Gauge.builder(METRIC_PREFIX + "_failure_count", circuitBreaker, CircuitBreaker::getFailureCount)
                .description("Current failure count")
                .tags(baseTags)
                .register(registry);

        Gauge.builder(METRIC_PREFIX + "_success_count", circuitBreaker, CircuitBreaker::getSuccessCount)
                .description("Current success count in half-open state")
                .tags(baseTags)
                .register(registry);

        // Register state change listener
        circuitBreaker.onStateChange((from, to) -> {
            stateTransitionCounter.increment();
            // Also publish state change as tagged counter for detailed tracking
            Counter.builder(METRIC_PREFIX + "_state_change")
                    .tags(baseTags.and("from", from.name(), "to", to.name()))
                    .register(registry)
                    .increment();
        });
    }

    /**
     * Creates or retrieves a metrics wrapper for the given circuit breaker.
     */
    public static CircuitBreakerMetrics of(CircuitBreaker circuitBreaker, MeterRegistry registry) {
        return instances.computeIfAbsent(circuitBreaker.getName(),
                name -> new CircuitBreakerMetrics(circuitBreaker, registry));
    }

    /**
     * Executes the given callable through the circuit breaker with metrics.
     */
    public <T> T execute(Callable<T> callable) throws Exception {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = circuitBreaker.execute(callable);
            successCounter.increment();
            return result;
        } catch (CircuitBreakerOpenException e) {
            rejectedCounter.increment();
            throw e;
        } catch (Exception e) {
            failureCounter.increment();
            throw e;
        } finally {
            sample.stop(callTimer);
        }
    }

    /**
     * Executes with fallback and metrics.
     */
    public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = circuitBreaker.executeWithFallback(callable, fallback);
            // Check if fallback was used by checking circuit state
            if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                rejectedCounter.increment();
            } else {
                successCounter.increment();
            }
            return result;
        } catch (Exception e) {
            failureCounter.increment();
            throw e;
        } finally {
            sample.stop(callTimer);
        }
    }

    /**
     * Gets the underlying circuit breaker.
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Gets total call count across all results.
     */
    public double getTotalCalls() {
        return successCounter.count() + failureCounter.count() + rejectedCounter.count();
    }

    /**
     * Gets the success rate as a percentage.
     */
    public double getSuccessRate() {
        double total = getTotalCalls();
        if (total == 0) return 100.0;
        return (successCounter.count() / total) * 100.0;
    }

    private double stateToNumber(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
        };
    }
}
