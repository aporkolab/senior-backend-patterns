package com.aporkolab.patterns.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.jupiter.api.extension.RegisterExtension;


class TracedCircuitBreakerTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

    private CircuitBreaker circuitBreaker;
    private TracedCircuitBreaker tracedCircuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.builder()
                .name("test-cb")
                .failureThreshold(3)
                .successThreshold(2)
                .openDurationMs(1000)
                .build();

        tracedCircuitBreaker = TracedCircuitBreaker.wrap(
                circuitBreaker,
                otelTesting.getOpenTelemetry().getTracer("test")
        );
    }

    @Test
    @DisplayName("should create span for successful execution")
    void shouldCreateSpanForSuccessfulExecution() {
        String result = tracedCircuitBreaker.execute(() -> "success");

        assertThat(result).isEqualTo("success");

        List<SpanData> spans = otelTesting.getSpans();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("circuit_breaker.execute");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("circuit_breaker.name")))
                .isEqualTo("test-cb");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("circuit_breaker.result")))
                .isEqualTo("success");
    }

    @Test
    @DisplayName("should create span for failed execution")
    void shouldCreateSpanForFailedExecution() {
        assertThatThrownBy(() -> tracedCircuitBreaker.execute(() -> {
            throw new RuntimeException("test error");
        })).isInstanceOf(RuntimeException.class);

        List<SpanData> spans = otelTesting.getSpans();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("circuit_breaker.execute");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("circuit_breaker.result")))
                .isEqualTo("failure");
    }

    @Test
    @DisplayName("should create span for rejected execution when circuit is open")
    void shouldCreateSpanForRejectedExecution() {
        
        for (int i = 0; i < 3; i++) {
            try {
                tracedCircuitBreaker.execute(() -> {
                    throw new RuntimeException("failure");
                });
            } catch (RuntimeException ignored) {}
        }

        otelTesting.clearSpans();

        
        assertThatThrownBy(() -> tracedCircuitBreaker.execute(() -> "should be rejected"))
                .isInstanceOf(CircuitBreakerOpenException.class);

        List<SpanData> spans = otelTesting.getSpans();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("circuit_breaker.result")))
                .isEqualTo("rejected");
    }

    @Test
    @DisplayName("should create span for state transitions")
    void shouldCreateSpanForStateTransitions() {
        
        for (int i = 0; i < 3; i++) {
            try {
                tracedCircuitBreaker.execute(() -> {
                    throw new RuntimeException("failure");
                });
            } catch (RuntimeException ignored) {}
        }

        List<SpanData> spans = otelTesting.getSpans();

        
        boolean hasStateChangeSpan = spans.stream()
                .anyMatch(s -> s.getName().equals("circuit_breaker.state_change"));
        assertThat(hasStateChangeSpan).isTrue();

        SpanData stateChangeSpan = spans.stream()
                .filter(s -> s.getName().equals("circuit_breaker.state_change"))
                .findFirst()
                .orElseThrow();

        assertThat(stateChangeSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("circuit_breaker.state.from")))
                .isEqualTo("CLOSED");
        assertThat(stateChangeSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("circuit_breaker.state.to")))
                .isEqualTo("OPEN");
    }

    @Test
    @DisplayName("should execute with fallback and create span")
    void shouldExecuteWithFallbackAndCreateSpan() {
        String result = tracedCircuitBreaker.executeWithFallback(
                () -> "primary",
                () -> "fallback"
        );

        assertThat(result).isEqualTo("primary");

        List<SpanData> spans = otelTesting.getSpans();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("circuit_breaker.execute_with_fallback");
    }

    @Test
    @DisplayName("should return delegate circuit breaker")
    void shouldReturnDelegateCircuitBreaker() {
        assertThat(tracedCircuitBreaker.getDelegate()).isSameAs(circuitBreaker);
    }

    @Test
    @DisplayName("should execute runnable")
    void shouldExecuteRunnable() {
        boolean[] executed = {false};

        tracedCircuitBreaker.execute(() -> executed[0] = true);

        assertThat(executed[0]).isTrue();
        assertThat(otelTesting.getSpans()).hasSize(1);
    }
}
