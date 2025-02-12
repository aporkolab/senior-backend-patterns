package com.aporkolab.patterns.resilience.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for CircuitBreaker.
 * Tests cover all state transitions, edge cases, and concurrent scenarios.
 */
class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.builder()
                .name("test-circuit")
                .failureThreshold(3)
                .successThreshold(2)
                .openDurationMs(100)
                .build();
    }

    @Nested
    @DisplayName("Initial State")
    class InitialState {

        @Test
        @DisplayName("should start in CLOSED state")
        void shouldStartInClosedState() {
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("should have zero failure count initially")
        void shouldHaveZeroFailureCount() {
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }

        @Test
        @DisplayName("should allow requests when closed")
        void shouldAllowRequestsWhenClosed() {
            assertThat(circuitBreaker.allowRequest()).isTrue();
        }

        @Test
        @DisplayName("should use provided name")
        void shouldUseProvidedName() {
            assertThat(circuitBreaker.getName()).isEqualTo("test-circuit");
        }
    }

    @Nested
    @DisplayName("Successful Execution")
    class SuccessfulExecution {

        @Test
        @DisplayName("should return result on successful execution")
        void shouldReturnResultOnSuccess() {
            String result = circuitBreaker.execute(() -> "success");
            assertThat(result).isEqualTo("success");
        }

        @Test
        @DisplayName("should reset failure count on success")
        void shouldResetFailureCountOnSuccess() {
            // Cause some failures (but not enough to open)
            causeFailures(2);
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(2);

            // Success should reset
            circuitBreaker.execute(() -> "success");
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }

        @Test
        @DisplayName("should remain closed after multiple successes")
        void shouldRemainClosedAfterSuccesses() {
            for (int i = 0; i < 10; i++) {
                circuitBreaker.execute(() -> "success");
            }
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    @Nested
    @DisplayName("Failure Handling")
    class FailureHandling {

        @Test
        @DisplayName("should increment failure count on exception")
        void shouldIncrementFailureCountOnException() {
            causeFailures(1);
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should propagate exception to caller")
        void shouldPropagateException() {
            assertThatThrownBy(() -> circuitBreaker.execute(() -> {
                throw new RuntimeException("test error");
            })).isInstanceOf(RuntimeException.class)
               .hasMessage("test error");
        }

        @Test
        @DisplayName("should open circuit after reaching failure threshold")
        void shouldOpenAfterThreshold() {
            causeFailures(3); // threshold is 3
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("should not open circuit before reaching threshold")
        void shouldNotOpenBeforeThreshold() {
            causeFailures(2); // threshold is 3
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    @Nested
    @DisplayName("Open State Behavior")
    class OpenStateBehavior {

        @BeforeEach
        void openCircuit() {
            causeFailures(3);
        }

        @Test
        @DisplayName("should reject requests when open")
        void shouldRejectRequestsWhenOpen() {
            assertThat(circuitBreaker.allowRequest()).isFalse();
        }

        @Test
        @DisplayName("should throw CircuitBreakerOpenException on execute")
        void shouldThrowOnExecute() {
            assertThatThrownBy(() -> circuitBreaker.execute(() -> "test"))
                    .isInstanceOf(CircuitBreakerOpenException.class)
                    .hasMessageContaining("test-circuit")
                    .hasMessageContaining("OPEN");
        }

        @Test
        @DisplayName("should use fallback when circuit is open")
        void shouldUseFallbackWhenOpen() {
            String result = circuitBreaker.executeWithFallback(
                    () -> "primary",
                    () -> "fallback"
            );
            assertThat(result).isEqualTo("fallback");
        }
    }

    @Nested
    @DisplayName("Half-Open State Transition")
    class HalfOpenTransition {

        @Test
        @DisplayName("should transition to HALF_OPEN after open duration")
        void shouldTransitionToHalfOpen() throws InterruptedException {
            causeFailures(3);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            Thread.sleep(150); // wait for open duration (100ms) + buffer

            assertThat(circuitBreaker.allowRequest()).isTrue();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        }

        @Test
        @DisplayName("should close circuit after success threshold in HALF_OPEN")
        void shouldCloseAfterSuccessThreshold() throws InterruptedException {
            causeFailures(3);
            Thread.sleep(150);

            // Two successes needed (successThreshold = 2)
            circuitBreaker.execute(() -> "success1");
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            circuitBreaker.execute(() -> "success2");
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("should reopen circuit on failure in HALF_OPEN")
        void shouldReopenOnFailureInHalfOpen() throws InterruptedException {
            causeFailures(3);
            Thread.sleep(150);

            // First request allowed, then fail
            assertThatThrownBy(() -> circuitBreaker.execute(() -> {
                throw new RuntimeException("still failing");
            })).isInstanceOf(RuntimeException.class);

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    @DisplayName("State Change Listener")
    class StateChangeListener {

        @Test
        @DisplayName("should notify listener on state change")
        void shouldNotifyListener() {
            List<String> transitions = new ArrayList<>();
            circuitBreaker.onStateChange((from, to) ->
                    transitions.add(from + " -> " + to));

            causeFailures(3);

            assertThat(transitions).containsExactly("CLOSED -> OPEN");
        }

        @Test
        @DisplayName("should capture all transitions")
        void shouldCaptureAllTransitions() throws InterruptedException {
            List<String> transitions = new ArrayList<>();
            circuitBreaker.onStateChange((from, to) ->
                    transitions.add(from + " -> " + to));

            causeFailures(3); // CLOSED -> OPEN
            Thread.sleep(150);
            circuitBreaker.execute(() -> "s1"); // OPEN -> HALF_OPEN
            circuitBreaker.execute(() -> "s2"); // HALF_OPEN -> CLOSED

            assertThat(transitions).containsExactly(
                    "CLOSED -> OPEN",
                    "OPEN -> HALF_OPEN",
                    "HALF_OPEN -> CLOSED"
            );
        }
    }

    @Nested
    @DisplayName("Manual Controls")
    class ManualControls {

        @Test
        @DisplayName("should allow manual trip")
        void shouldAllowManualTrip() {
            circuitBreaker.trip();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("should allow manual reset")
        void shouldAllowManualReset() {
            causeFailures(3);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            circuitBreaker.reset();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("reset should clear failure count")
        void resetShouldClearFailureCount() {
            causeFailures(2);
            circuitBreaker.reset();
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("should reject invalid failure threshold")
        void shouldRejectInvalidFailureThreshold() {
            assertThatThrownBy(() -> CircuitBreaker.builder()
                    .failureThreshold(0)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("failureThreshold");
        }

        @Test
        @DisplayName("should reject invalid success threshold")
        void shouldRejectInvalidSuccessThreshold() {
            assertThatThrownBy(() -> CircuitBreaker.builder()
                    .successThreshold(0)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("successThreshold");
        }

        @Test
        @DisplayName("should reject invalid open duration")
        void shouldRejectInvalidOpenDuration() {
            assertThatThrownBy(() -> CircuitBreaker.builder()
                    .openDurationMs(50) // < 100
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("openDurationMs");
        }

        @Test
        @DisplayName("should use default values")
        void shouldUseDefaultValues() {
            CircuitBreaker cb = CircuitBreaker.builder().build();
            assertThat(cb.getName()).isEqualTo("default");
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    @Nested
    @DisplayName("Runnable Execution")
    class RunnableExecution {

        @Test
        @DisplayName("should execute runnable successfully")
        void shouldExecuteRunnable() {
            AtomicInteger counter = new AtomicInteger(0);
            circuitBreaker.execute(counter::incrementAndGet);
            assertThat(counter.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle runnable failure")
        void shouldHandleRunnableFailure() {
            assertThatThrownBy(() -> circuitBreaker.execute((Runnable) () -> {
                throw new RuntimeException("runnable failed");
            })).isInstanceOf(RuntimeException.class)
               .hasMessage("runnable failed");

            assertThat(circuitBreaker.getFailureCount()).isEqualTo(1);
        }
    }

    // Helper method
    private void causeFailures(int count) {
        for (int i = 0; i < count; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new RuntimeException("simulated failure");
                });
            } catch (RuntimeException ignored) {
                // Expected
            }
        }
    }
}
