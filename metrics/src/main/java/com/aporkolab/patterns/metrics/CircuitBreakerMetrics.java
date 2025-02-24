package com.aporkolab.patterns.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;


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

    
    private final ConcurrentMap<String, Counter> stateChangeCounters = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, CircuitBreakerMetrics> instances = new ConcurrentHashMap<>();

    private CircuitBreakerMetrics(CircuitBreaker circuitBreaker, MeterRegistry registry) {
        this.circuitBreaker = circuitBreaker;
        this.registry = registry;
        this.baseTags = Tags.of("name", circuitBreaker.getName());

        
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

        
        this.callTimer = Timer.builder(METRIC_PREFIX + "_call_duration")
                .description("Circuit breaker call duration")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        
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

        
        circuitBreaker.onStateChange((from, to) -> {
            stateTransitionCounter.increment();
            
            String key = from.name() + "->" + to.name();
            Counter counter = stateChangeCounters.computeIfAbsent(key, k ->
                    Counter.builder(METRIC_PREFIX + "_state_change")
                            .tags(baseTags.and("from", from.name(), "to", to.name()))
                            .register(registry));
            counter.increment();
        });
    }

    
    public static CircuitBreakerMetrics of(CircuitBreaker circuitBreaker, MeterRegistry registry) {
        return instances.computeIfAbsent(circuitBreaker.getName(),
                name -> new CircuitBreakerMetrics(circuitBreaker, registry));
    }

    
    public <T> T execute(Supplier<T> supplier) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = circuitBreaker.execute(supplier);
            successCounter.increment();
            return result;
        } catch (CircuitBreakerOpenException e) {
            rejectedCounter.increment();
            throw e;
        } catch (RuntimeException e) {
            failureCounter.increment();
            throw e;
        } finally {
            sample.stop(callTimer);
        }
    }

    
    public <T> T executeWithFallback(Supplier<T> supplier, Supplier<T> fallback) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = circuitBreaker.executeWithFallback(supplier, fallback);
            
            if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                rejectedCounter.increment();
            } else {
                successCounter.increment();
            }
            return result;
        } catch (RuntimeException e) {
            failureCounter.increment();
            throw e;
        } finally {
            sample.stop(callTimer);
        }
    }

    
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    
    public double getTotalCalls() {
        return successCounter.count() + failureCounter.count() + rejectedCounter.count();
    }

    
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
