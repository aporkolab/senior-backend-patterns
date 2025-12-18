package com.aporkolab.patterns.resilience.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concurrency tests for CircuitBreaker.
 * Validates thread-safety of the lock-free implementation.
 */
class CircuitBreakerConcurrencyTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.builder()
                .name("concurrent-test")
                .failureThreshold(50)
                .successThreshold(10)
                .openDurationMs(100)
                .build();
    }

    @Test
    @DisplayName("should handle concurrent successful requests")
    void shouldHandleConcurrentSuccesses() throws InterruptedException {
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    String result = circuitBreaker.execute(() -> "success");
                    if ("success".equals(result)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should handle concurrent failures without race conditions")
    void shouldHandleConcurrentFailures() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.builder()
                .name("race-test")
                .failureThreshold(10)
                .successThreshold(5)
                .openDurationMs(100)
                .build();

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // All threads start together
                    cb.execute(() -> {
                        throw new RuntimeException("concurrent failure");
                    });
                } catch (CircuitBreakerOpenException e) {
                    // Expected after circuit opens
                    exceptionCount.incrementAndGet();
                } catch (Exception ignored) {
                    // RuntimeException from supplier
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all threads
        endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Circuit should be open after 10 failures
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // Some requests should have been rejected
        assertThat(exceptionCount.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("should handle mixed success/failure concurrently")
    void shouldHandleMixedOperations() throws InterruptedException {
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger index = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    int idx = index.getAndIncrement();
                    if (idx % 3 == 0) {
                        // Every 3rd request fails
                        circuitBreaker.execute(() -> {
                            throw new RuntimeException("failure");
                        });
                    } else {
                        circuitBreaker.execute(() -> "success");
                    }
                } catch (Exception ignored) {
                    // Expected
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Should remain closed (failures < 50 threshold due to interleaved successes resetting count)
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should correctly transition through half-open with concurrent requests")
    void shouldTransitionCorrectlyUnderConcurrency() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.builder()
                .name("transition-test")
                .failureThreshold(5)
                .successThreshold(3)
                .openDurationMs(50)
                .build();

        // Open the circuit
        for (int i = 0; i < 5; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Wait for half-open transition
        Thread.sleep(100);

        // Send concurrent success requests
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    cb.execute(() -> "success");
                    successCount.incrementAndGet();
                } catch (CircuitBreakerOpenException e) {
                    rejectedCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Circuit should be closed after enough successes
        // Some requests may have been rate-limited in half-open
        assertThat(successCount.get()).isGreaterThanOrEqualTo(3);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("state change listener should be called thread-safely")
    void shouldCallListenerThreadSafely() throws InterruptedException {
        AtomicInteger listenerCallCount = new AtomicInteger(0);
        
        CircuitBreaker cb = CircuitBreaker.builder()
                .name("listener-test")
                .failureThreshold(5)
                .successThreshold(2)
                .openDurationMs(50)
                .build();

        cb.onStateChange((from, to) -> listenerCallCount.incrementAndGet());

        // Rapidly trip and reset
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(20);

        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                cb.trip();
                latch.countDown();
            });
            executor.submit(() -> {
                cb.reset();
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Listener should have been called multiple times
        assertThat(listenerCallCount.get()).isGreaterThan(0);
    }
}
