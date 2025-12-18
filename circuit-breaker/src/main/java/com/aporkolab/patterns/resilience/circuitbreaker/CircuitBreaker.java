package com.aporkolab.patterns.resilience.circuitbreaker;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED,    
        OPEN,      
        HALF_OPEN  
    }

    private final String name;
    private final int failureThreshold;
    private final int successThreshold;
    private final Duration openDuration;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();
    private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);

    private volatile BiConsumer<State, State> stateChangeListener;

    private CircuitBreaker(Builder builder) {
        this.name = builder.name;
        this.failureThreshold = builder.failureThreshold;
        this.successThreshold = builder.successThreshold;
        this.openDuration = Duration.ofMillis(builder.openDurationMs);
    }

    public static Builder builder() {
        return new Builder();
    }

    
    public <T> T execute(Supplier<T> supplier) {
        if (!allowRequest()) {
            throw new CircuitBreakerOpenException(name);
        }

        try {
            T result = supplier.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    
    public <T> T executeWithFallback(Supplier<T> supplier, Supplier<T> fallback) {
        try {
            return execute(supplier);
        } catch (CircuitBreakerOpenException e) {
            log.debug("Circuit {} open, using fallback", name);
            return fallback.get();
        }
    }

    
    public void execute(Runnable runnable) {
        execute(() -> {
            runnable.run();
            return null;
        });
    }

    
    public boolean allowRequest() {
        State currentState = state.get();

        switch (currentState) {
            case CLOSED:
                return true;

            case OPEN:
                if (shouldTransitionToHalfOpen()) {
                    transitionTo(State.HALF_OPEN);
                    return true;
                }
                return false;

            case HALF_OPEN:
                
                return halfOpenAttempts.incrementAndGet() <= successThreshold;

            default:
                return false;
        }
    }

    private boolean shouldTransitionToHalfOpen() {
        Instant opened = openedAt.get();
        return opened != null && Instant.now().isAfter(opened.plus(openDuration));
    }

    private void recordSuccess() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            if (successes >= successThreshold) {
                transitionTo(State.CLOSED);
            }
        } else if (currentState == State.CLOSED) {
            
            failureCount.set(0);
        }
    }

    private void recordFailure() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            
            transitionTo(State.OPEN);
        } else if (currentState == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                transitionTo(State.OPEN);
            }
        }
    }

    private void transitionTo(State newState) {
        State oldState = state.getAndSet(newState);

        if (oldState != newState) {
            log.info("Circuit breaker {} transitioning: {} -> {}", name, oldState, newState);

            switch (newState) {
                case OPEN:
                    openedAt.set(Instant.now());
                    failureCount.set(0);
                    break;
                case HALF_OPEN:
                    successCount.set(0);
                    halfOpenAttempts.set(0);
                    break;
                case CLOSED:
                    failureCount.set(0);
                    successCount.set(0);
                    break;
            }

            if (stateChangeListener != null) {
                stateChangeListener.accept(oldState, newState);
            }
        }
    }

    
    public void onStateChange(BiConsumer<State, State> listener) {
        this.stateChangeListener = listener;
    }

    
    public void reset() {
        transitionTo(State.CLOSED);
        
        failureCount.set(0);
        successCount.set(0);
    }

    
    public void trip() {
        transitionTo(State.OPEN);
    }

    
    public String getName() {
        return name;
    }

    public State getState() {
        return state.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public static class Builder {
        private String name = "default";
        private int failureThreshold = 5;
        private int successThreshold = 3;
        private long openDurationMs = 30000;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder failureThreshold(int failureThreshold) {
            if (failureThreshold < 1) {
                throw new IllegalArgumentException("failureThreshold must be >= 1");
            }
            this.failureThreshold = failureThreshold;
            return this;
        }

        public Builder successThreshold(int successThreshold) {
            if (successThreshold < 1) {
                throw new IllegalArgumentException("successThreshold must be >= 1");
            }
            this.successThreshold = successThreshold;
            return this;
        }

        public Builder openDurationMs(long openDurationMs) {
            if (openDurationMs < 100) {
                throw new IllegalArgumentException("openDurationMs must be >= 100");
            }
            this.openDurationMs = openDurationMs;
            return this;
        }

        public CircuitBreaker build() {
            return new CircuitBreaker(this);
        }
    }
}
