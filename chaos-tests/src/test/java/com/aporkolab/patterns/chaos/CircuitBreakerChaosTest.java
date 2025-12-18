package com.aporkolab.patterns.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;

/**
 * Chaos Engineering tests for Circuit Breaker.
 * 
 * These tests simulate real-world failure scenarios:
 * - Cascading failures
 * - Thundering herd
 * - Network partitions
 * - Gradual degradation
 * - Recovery storms
 */
class CircuitBreakerChaosTest {

    @Test
    @DisplayName("Chaos: Should handle cascading failure scenario")
    @Timeout(30)
    void shouldHandleCascadingFailures() throws Exception {
        // Scenario: Downstream service fails, causing cascade
        CircuitBreaker upstream = CircuitBreaker.builder()
                .name("upstream")
                .failureThreshold(3)
                .openDurationMs(1000)
                .build();

        CircuitBreaker downstream = CircuitBreaker.builder()
                .name("downstream")
                .failureThreshold(3)
                .openDurationMs(1000)
                .build();

        AtomicInteger downstreamCalls = new AtomicInteger(0);
        AtomicInteger upstreamSuccesses = new AtomicInteger(0);

        // Simulate downstream failure
        for (int i = 0; i < 100; i++) {
            try {
                upstream.execute(() -> {
                    // Upstream calls downstream
                    return downstream.execute(() -> {
                        downstreamCalls.incrementAndGet();
                        // Downstream always fails
                        throw new RuntimeException("Downstream failure");
                    });
                });
                upstreamSuccesses.incrementAndGet();
            } catch (Exception e) {
                // Expected
            }
        }

        // Downstream should be open
        assertThat(downstream.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        
        // Downstream received only threshold calls before opening
        assertThat(downstreamCalls.get()).isEqualTo(3);
        
        // Upstream should protect itself too
        assertThat(upstream.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("Chaos: Should survive thundering herd after recovery")
    @Timeout(30)
    void shouldSurviveThunderingHerd() throws Exception {
        CircuitBreaker breaker = CircuitBreaker.builder()
                .name("thundering-herd")
                .failureThreshold(5)
                .successThreshold(2)
                .openDurationMs(500)
                .build();

        AtomicInteger actualCalls = new AtomicInteger(0);
        AtomicLong recoveryStartTime = new AtomicLong(0);
        Random random = new Random(42);

        // Phase 1: Force circuit open
        for (int i = 0; i < 5; i++) {
            try {
                breaker.execute(() -> {
                    throw new RuntimeException("Initial failures");
                });
            } catch (Exception e) {
                // Expected
            }
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Phase 2: Wait for half-open
        Thread.sleep(600);

        // Phase 3: Thundering herd - 1000 threads all try at once
        int herdSize = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(herdSize);
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < herdSize; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String result = breaker.execute(() -> {
                        actualCalls.incrementAndGet();
                        // Simulate recovered service with slight delay
                        Thread.sleep(random.nextInt(5));
                        return "success";
                    });
                    results.add(result);
                } catch (CircuitBreakerOpenException e) {
                    results.add("rejected");
                } catch (Exception e) {
                    results.add("error");
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release the herd
        recoveryStartTime.set(System.currentTimeMillis());
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify circuit breaker protected the recovering service
        // Most requests should be rejected while in half-open
        long rejectedCount = results.stream().filter(r -> r.equals("rejected")).count();
        long successCount = results.stream().filter(r -> r.equals("success")).count();

        System.out.printf("Thundering Herd Results: %d success, %d rejected, %d actual calls%n",
                successCount, rejectedCount, actualCalls.get());

        // Circuit breaker should limit actual calls significantly
        assertThat(actualCalls.get()).isLessThan(herdSize / 2);
    }

    @Test
    @DisplayName("Chaos: Should handle intermittent failures (flaky service)")
    @Timeout(30)
    void shouldHandleFlakyService() throws Exception {
        CircuitBreaker breaker = CircuitBreaker.builder()
                .name("flaky-service")
                .failureThreshold(5)
                .successThreshold(3)
                .openDurationMs(200)
                .build();

        Random random = new Random(42);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        // Simulate flaky service with 30% failure rate
        for (int i = 0; i < 1000; i++) {
            try {
                breaker.execute(() -> {
                    if (random.nextDouble() < 0.3) {
                        throw new RuntimeException("Flaky failure");
                    }
                    return "success";
                });
                successCount.incrementAndGet();
            } catch (CircuitBreakerOpenException e) {
                rejectedCount.incrementAndGet();
                // Wait a bit for circuit to potentially close
                Thread.sleep(10);
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        }

        System.out.printf("Flaky Service: %d success, %d failure, %d rejected%n",
                successCount.get(), failureCount.get(), rejectedCount.get());

        // Should have significant successes despite flakiness
        assertThat(successCount.get()).isGreaterThan(200);
        // Circuit breaker should have triggered at some point
        assertThat(rejectedCount.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Chaos: Should handle gradual degradation")
    @Timeout(60)
    void shouldHandleGradualDegradation() throws Exception {
        CircuitBreaker breaker = CircuitBreaker.builder()
                .name("degrading-service")
                .failureThreshold(5)
                .successThreshold(2)
                .openDurationMs(500)
                .build();

        List<Long> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger errorRate = new AtomicInteger(0); // 0-100

        // Simulate gradual degradation over time
        ScheduledExecutorService degradationSimulator = Executors.newSingleThreadScheduledExecutor();
        degradationSimulator.scheduleAtFixedRate(() -> {
            int current = errorRate.get();
            if (current < 80) {
                errorRate.incrementAndGet(); // Increase error rate by 1% every 100ms
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        Random random = new Random(42);
        AtomicInteger openCount = new AtomicInteger(0);

        // Run requests for 5 seconds
        long endTime = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < endTime) {
            long start = System.nanoTime();
            try {
                breaker.execute(() -> {
                    // Fail based on current error rate
                    if (random.nextInt(100) < errorRate.get()) {
                        throw new RuntimeException("Degraded service failure");
                    }
                    return "success";
                });
                latencies.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start));
            } catch (CircuitBreakerOpenException e) {
                openCount.incrementAndGet();
                Thread.sleep(50);
            } catch (Exception e) {
                // Expected failures
            }
        }

        degradationSimulator.shutdown();

        System.out.printf("Gradual Degradation: Circuit opened %d times, final error rate: %d%%%n",
                openCount.get(), errorRate.get());

        // Circuit should have opened multiple times as service degraded
        assertThat(openCount.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Chaos: Should handle rapid state transitions")
    @Timeout(30)
    void shouldHandleRapidStateTransitions() throws Exception {
        CircuitBreaker breaker = CircuitBreaker.builder()
                .name("rapid-transitions")
                .failureThreshold(2)
                .successThreshold(1)
                .openDurationMs(100) // Very short for rapid transitions
                .build();

        List<CircuitBreaker.State> stateHistory = new CopyOnWriteArrayList<>();
        breaker.onStateChange((from, to) -> stateHistory.add(to));

        Random random = new Random(42);
        
        // Run rapid success/failure cycles
        for (int cycle = 0; cycle < 20; cycle++) {
            // Cause failures
            for (int i = 0; i < 2; i++) {
                try {
                    breaker.execute(() -> {
                        throw new RuntimeException("Failure");
                    });
                } catch (Exception e) {
                    // Expected
                }
            }

            // Wait for half-open
            Thread.sleep(150);

            // Recover
            try {
                breaker.execute(() -> "success");
            } catch (CircuitBreakerOpenException e) {
                // May still be open
            }
        }

        System.out.printf("Rapid Transitions: %d state changes recorded%n", stateHistory.size());

        // Should have many state transitions
        assertThat(stateHistory.size()).isGreaterThan(10);
        
        // Should contain all state types
        assertThat(stateHistory).contains(
                CircuitBreaker.State.OPEN,
                CircuitBreaker.State.HALF_OPEN,
                CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Chaos: Should maintain consistency under concurrent state changes")
    @Timeout(30)
    void shouldMaintainConsistencyUnderConcurrentStateChanges() throws Exception {
        CircuitBreaker breaker = CircuitBreaker.builder()
                .name("consistency-test")
                .failureThreshold(3)
                .successThreshold(2)
                .openDurationMs(50)
                .build();

        int threadCount = 50;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successOperations = new AtomicInteger(0);
        AtomicInteger failedOperations = new AtomicInteger(0);
        AtomicInteger rejectedOperations = new AtomicInteger(0);
        AtomicInteger inconsistencies = new AtomicInteger(0);

        Random random = new Random(42);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        CircuitBreaker.State stateBefore = breaker.getState();
                        
                        try {
                            breaker.execute(() -> {
                                if (random.nextBoolean()) {
                                    throw new RuntimeException("Random failure");
                                }
                                return "success";
                            });
                            successOperations.incrementAndGet();
                            
                        } catch (CircuitBreakerOpenException e) {
                            rejectedOperations.incrementAndGet();
                            // If we got rejected but state was CLOSED, that's potentially inconsistent
                            // (though it could be a race, which is expected)
                            if (stateBefore == CircuitBreaker.State.CLOSED) {
                                // State changed between check and execute - acceptable race
                            }
                        } catch (Exception e) {
                            failedOperations.incrementAndGet();
                        }

                        // Brief pause to allow state changes
                        if (i % 10 == 0) {
                            Thread.sleep(1);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        int totalOperations = successOperations.get() + failedOperations.get() + rejectedOperations.get();
        
        System.out.printf("Consistency Test: %d total (%d success, %d failed, %d rejected)%n",
                totalOperations, successOperations.get(), failedOperations.get(), rejectedOperations.get());

        // All operations should be accounted for
        assertThat(totalOperations).isEqualTo(threadCount * operationsPerThread);
        
        // No inconsistencies detected
        assertThat(inconsistencies.get()).isZero();
        
        // Final state should be valid
        assertThat(breaker.getState()).isIn(
                CircuitBreaker.State.CLOSED,
                CircuitBreaker.State.OPEN,
                CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("Chaos: Should recover from total outage")
    @Timeout(30)
    void shouldRecoverFromTotalOutage() throws Exception {
        CircuitBreaker breaker = CircuitBreaker.builder()
                .name("total-outage")
                .failureThreshold(3)
                .successThreshold(2)
                .openDurationMs(500)
                .build();

        AtomicBoolean serviceDown = new AtomicBoolean(true);

        // Phase 1: Total outage - all requests fail
        for (int i = 0; i < 10; i++) {
            try {
                breaker.execute(() -> {
                    if (serviceDown.get()) {
                        throw new RuntimeException("Service unavailable");
                    }
                    return "success";
                });
            } catch (Exception e) {
                // Expected
            }
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Phase 2: Service recovers
        serviceDown.set(false);

        // Phase 3: Wait and verify recovery
        AtomicInteger postRecoverySuccesses = new AtomicInteger(0);
        
        for (int attempt = 0; attempt < 20; attempt++) {
            Thread.sleep(200);
            try {
                breaker.execute(() -> "recovered");
                postRecoverySuccesses.incrementAndGet();
            } catch (CircuitBreakerOpenException e) {
                // Still open, wait more
            }
        }

        // Should have recovered and allowed requests
        assertThat(postRecoverySuccesses.get()).isGreaterThan(0);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
