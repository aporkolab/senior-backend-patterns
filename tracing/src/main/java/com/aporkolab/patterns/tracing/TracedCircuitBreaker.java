package com.aporkolab.patterns.tracing;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * OpenTelemetry tracing wrapper for Circuit Breaker.
 * 
 * Automatically creates spans for:
 * - Circuit breaker executions
 * - State transitions
 * - Failures and rejections
 * 
 * Span attributes include:
 * - circuit_breaker.name
 * - circuit_breaker.state
 * - circuit_breaker.failure_count
 * - circuit_breaker.success_count
 */
public class TracedCircuitBreaker {

    private static final String INSTRUMENTATION_NAME = "com.aporkolab.patterns.circuit-breaker";

    private final CircuitBreaker delegate;
    private final Tracer tracer;

    public TracedCircuitBreaker(CircuitBreaker delegate) {
        this(delegate, GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME));
    }

    public TracedCircuitBreaker(CircuitBreaker delegate, Tracer tracer) {
        this.delegate = delegate;
        this.tracer = tracer;

        // Register state change listener for tracing
        delegate.onStateChange((from, to) -> {
            Span stateChangeSpan = tracer.spanBuilder("circuit_breaker.state_change")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("circuit_breaker.name", delegate.getName())
                    .setAttribute("circuit_breaker.state.from", from.name())
                    .setAttribute("circuit_breaker.state.to", to.name())
                    .startSpan();
            stateChangeSpan.end();
        });
    }

    /**
     * Execute with tracing.
     */
    public <T> T execute(Callable<T> callable) throws Exception {
        Span span = tracer.spanBuilder("circuit_breaker.execute")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("circuit_breaker.name", delegate.getName())
                .setAttribute("circuit_breaker.state", delegate.getState().name())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            T result = delegate.execute(callable);
            span.setAttribute("circuit_breaker.result", "success");
            span.setStatus(StatusCode.OK);
            return result;

        } catch (CircuitBreakerOpenException e) {
            span.setAttribute("circuit_breaker.result", "rejected");
            span.setAttribute("circuit_breaker.rejection_reason", "circuit_open");
            span.setStatus(StatusCode.ERROR, "Circuit breaker open");
            span.recordException(e);
            throw e;

        } catch (Exception e) {
            span.setAttribute("circuit_breaker.result", "failure");
            span.setAttribute("circuit_breaker.failure_count", delegate.getFailureCount());
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;

        } finally {
            span.setAttribute("circuit_breaker.state.after", delegate.getState().name());
            span.end();
        }
    }

    /**
     * Execute with fallback and tracing.
     */
    public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) {
        Span span = tracer.spanBuilder("circuit_breaker.execute_with_fallback")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("circuit_breaker.name", delegate.getName())
                .setAttribute("circuit_breaker.state", delegate.getState().name())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            T result = delegate.executeWithFallback(callable, () -> {
                span.setAttribute("circuit_breaker.fallback_used", true);
                return fallback.get();
            });
            span.setStatus(StatusCode.OK);
            return result;

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;

        } finally {
            span.end();
        }
    }

    /**
     * Execute a runnable with tracing.
     */
    public void execute(Runnable runnable) throws Exception {
        execute(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Get the underlying circuit breaker.
     */
    public CircuitBreaker getDelegate() {
        return delegate;
    }

    /**
     * Create a traced circuit breaker from an existing one.
     */
    public static TracedCircuitBreaker wrap(CircuitBreaker circuitBreaker) {
        return new TracedCircuitBreaker(circuitBreaker);
    }

    /**
     * Create a traced circuit breaker with custom tracer.
     */
    public static TracedCircuitBreaker wrap(CircuitBreaker circuitBreaker, Tracer tracer) {
        return new TracedCircuitBreaker(circuitBreaker, tracer);
    }
}
