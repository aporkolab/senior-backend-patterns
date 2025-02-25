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

import java.util.function.Supplier;


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

    
    public <T> T execute(Supplier<T> supplier) {
        Span span = tracer.spanBuilder("circuit_breaker.execute")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("circuit_breaker.name", delegate.getName())
                .setAttribute("circuit_breaker.state", delegate.getState().name())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            T result = delegate.execute(supplier);
            span.setAttribute("circuit_breaker.result", "success");
            span.setStatus(StatusCode.OK);
            return result;

        } catch (CircuitBreakerOpenException e) {
            span.setAttribute("circuit_breaker.result", "rejected");
            span.setAttribute("circuit_breaker.rejection_reason", "circuit_open");
            span.setStatus(StatusCode.ERROR, "Circuit breaker open");
            span.recordException(e);
            throw e;

        } catch (RuntimeException e) {
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

    
    public <T> T executeWithFallback(Supplier<T> supplier, Supplier<T> fallback) {
        Span span = tracer.spanBuilder("circuit_breaker.execute_with_fallback")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("circuit_breaker.name", delegate.getName())
                .setAttribute("circuit_breaker.state", delegate.getState().name())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            T result = delegate.executeWithFallback(supplier, () -> {
                span.setAttribute("circuit_breaker.fallback_used", true);
                return fallback.get();
            });
            span.setStatus(StatusCode.OK);
            return result;

        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;

        } finally {
            span.end();
        }
    }

    
    public void execute(Runnable runnable) {
        execute(() -> {
            runnable.run();
            return null;
        });
    }

    
    public CircuitBreaker getDelegate() {
        return delegate;
    }

    
    public static TracedCircuitBreaker wrap(CircuitBreaker circuitBreaker) {
        return new TracedCircuitBreaker(circuitBreaker);
    }

    
    public static TracedCircuitBreaker wrap(CircuitBreaker circuitBreaker, Tracer tracer) {
        return new TracedCircuitBreaker(circuitBreaker, tracer);
    }
}
