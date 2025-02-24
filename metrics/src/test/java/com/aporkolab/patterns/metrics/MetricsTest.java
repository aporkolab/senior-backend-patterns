package com.aporkolab.patterns.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;
import com.aporkolab.patterns.messaging.dlq.FailureType;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;


class MetricsTest {

    @Nested
    @DisplayName("CircuitBreakerMetrics")
    class CircuitBreakerMetricsTest {

        private MeterRegistry registry;
        private CircuitBreaker circuitBreaker;
        private CircuitBreakerMetrics metrics;
        private static int testCounter = 0;

        @BeforeEach
        void setUp() {
            registry = new SimpleMeterRegistry();
            
            String uniqueName = "test-breaker-" + (++testCounter);
            circuitBreaker = CircuitBreaker.builder()
                    .name(uniqueName)
                    .failureThreshold(3)
                    .successThreshold(2)
                    .openDurationMs(1000)
                    .build();
            metrics = CircuitBreakerMetrics.of(circuitBreaker, registry);
        }

        @Test
        @DisplayName("should track successful calls")
        void shouldTrackSuccessfulCalls() {
            metrics.execute(() -> "success");
            metrics.execute(() -> "success again");

            assertThat(metrics.getTotalCalls()).isEqualTo(2);
            assertThat(metrics.getSuccessRate()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("should track failed calls")
        void shouldTrackFailedCalls() {
            try {
                metrics.execute(() -> {
                    throw new RuntimeException("failure");
                });
            } catch (RuntimeException ignored) {}

            assertThat(metrics.getTotalCalls()).isEqualTo(1);
            assertThat(metrics.getSuccessRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should track rejected calls when circuit is open")
        void shouldTrackRejectedCalls() {
            
            for (int i = 0; i < 3; i++) {
                try {
                    metrics.execute(() -> {
                        throw new RuntimeException("failure");
                    });
                } catch (RuntimeException ignored) {}
            }

            
            assertThatThrownBy(() -> metrics.execute(() -> "should be rejected"))
                    .isInstanceOf(CircuitBreakerOpenException.class);

            
            assertThat(metrics.getTotalCalls()).isEqualTo(4);
        }

        @Test
        @DisplayName("should return underlying circuit breaker")
        void shouldReturnUnderlyingCircuitBreaker() {
            assertThat(metrics.getCircuitBreaker()).isSameAs(circuitBreaker);
        }

        @Test
        @DisplayName("should use cached instance for same circuit breaker name")
        void shouldUseCachedInstance() {
            
            MeterRegistry anotherRegistry = new SimpleMeterRegistry();
            CircuitBreakerMetrics metrics2 = CircuitBreakerMetrics.of(circuitBreaker, anotherRegistry);
            
            assertThat(metrics2).isSameAs(metrics);
        }

        @Test
        @DisplayName("should execute with fallback")
        void shouldExecuteWithFallback() {
            String result = metrics.executeWithFallback(
                    () -> "primary",
                    () -> "fallback"
            );

            assertThat(result).isEqualTo("primary");
            assertThat(metrics.getTotalCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("should return 100% success rate when no calls made")
        void shouldReturn100PercentWhenNoCalls() {
            assertThat(metrics.getSuccessRate()).isEqualTo(100.0);
            assertThat(metrics.getTotalCalls()).isEqualTo(0);
        }

        @Test
        @DisplayName("should register metrics in registry")
        void shouldRegisterMetricsInRegistry() {
            
            metrics.execute(() -> "test");

            String name = circuitBreaker.getName();

            
            assertThat(registry.find("circuit_breaker_calls_total")
                    .tags("name", name, "result", "success")
                    .counter()).isNotNull();

            assertThat(registry.find("circuit_breaker_state")
                    .tags("name", name)
                    .gauge()).isNotNull();

            assertThat(registry.find("circuit_breaker_call_duration")
                    .tags("name", name)
                    .timer()).isNotNull();
        }

        @Test
        @DisplayName("should track state transitions")
        void shouldTrackStateTransitions() {
            String name = circuitBreaker.getName();

            
            for (int i = 0; i < 3; i++) {
                try {
                    metrics.execute(() -> {
                        throw new RuntimeException("failure");
                    });
                } catch (RuntimeException ignored) {}
            }

            
            assertThat(registry.find("circuit_breaker_state_transitions_total")
                    .tags("name", name)
                    .counter()
                    .count()).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("DlqMetrics")
    class DlqMetricsTest {

        private MeterRegistry registry;
        private DlqMetrics dlqMetrics;

        @BeforeEach
        void setUp() {
            registry = new SimpleMeterRegistry();
            dlqMetrics = new DlqMetrics(registry, "test-dlq");
        }

        @Test
        @DisplayName("should track messages sent to DLQ")
        void shouldTrackMessagesSent() {
            dlqMetrics.recordMessageSent(FailureType.VALIDATION_ERROR);
            dlqMetrics.recordMessageSent(FailureType.VALIDATION_ERROR);
            dlqMetrics.recordMessageSent(FailureType.PERMANENT);

            assertThat(dlqMetrics.getTotalMessageCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should track failure type distribution")
        void shouldTrackFailureTypeDistribution() {
            dlqMetrics.recordMessageSent(FailureType.VALIDATION_ERROR);
            dlqMetrics.recordMessageSent(FailureType.VALIDATION_ERROR);
            dlqMetrics.recordMessageSent(FailureType.DESERIALIZATION_ERROR);

            var distribution = dlqMetrics.getFailureTypeDistribution();
            assertThat(distribution.get(FailureType.VALIDATION_ERROR)).isEqualTo(2L);
            assertThat(distribution.get(FailureType.DESERIALIZATION_ERROR)).isEqualTo(1L);
            assertThat(distribution.get(FailureType.PERMANENT)).isEqualTo(0L);
        }

        @Test
        @DisplayName("should track retry attempts")
        void shouldTrackRetryAttempts() {
            dlqMetrics.recordRetry();
            dlqMetrics.recordRetry();
            dlqMetrics.recordRetry();

            assertThat(dlqMetrics.getTotalRetryCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should track reprocess attempts and success")
        void shouldTrackReprocessAttempts() {
            dlqMetrics.recordReprocessAttempt();
            dlqMetrics.recordReprocessAttempt();
            dlqMetrics.recordReprocessSuccess();

            assertThat(dlqMetrics.getTotalReprocessCount()).isEqualTo(2);
            assertThat(dlqMetrics.getReprocessSuccessRate()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("should update DLQ depth")
        void shouldUpdateDepth() {
            dlqMetrics.updateDepth(100);
            assertThat(dlqMetrics.getCurrentDepth()).isEqualTo(100);

            dlqMetrics.updateDepth(50);
            assertThat(dlqMetrics.getCurrentDepth()).isEqualTo(50);
        }

        @Test
        @DisplayName("should track processing time")
        void shouldTrackProcessingTime() {
            dlqMetrics.recordProcessingTime(Duration.ofMillis(100));
            dlqMetrics.recordProcessingTime(Duration.ofMillis(200));

            
            assertThat(registry.find("dlq_processing_duration")
                    .tags("dlq_name", "test-dlq")
                    .timer()).isNotNull();
        }

        @Test
        @DisplayName("should return 0 reprocess success rate when no reprocess attempts")
        void shouldReturnZeroRateWhenNoReprocess() {
            assertThat(dlqMetrics.getReprocessSuccessRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should track messages by topic")
        void shouldTrackMessagesByTopic() {
            dlqMetrics.recordMessageSent("orders", FailureType.VALIDATION_ERROR);
            dlqMetrics.recordMessageSent("payments", FailureType.PERMANENT);

            
            assertThat(registry.find("dlq_messages_by_topic_total")
                    .tags("original_topic", "orders")
                    .counter()).isNotNull();
        }

        @Test
        @DisplayName("should update depth by topic")
        void shouldUpdateDepthByTopic() {
            dlqMetrics.updateDepthByTopic("orders", 50);
            dlqMetrics.updateDepthByTopic("payments", 30);

            assertThat(dlqMetrics.getDepthByTopic("orders")).isEqualTo(50);
            assertThat(dlqMetrics.getDepthByTopic("payments")).isEqualTo(30);
        }
    }
}
