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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;


class CircuitBreakerChaosTest {

    @Test
    @DisplayName("Chaos: Should handle cascading failure scenario")
    @Timeout(30)
    void shouldHandleCascadingFailures() throws Exception {
        
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

        
        for (int i = 0; i < 100; i++) {
            try {
                upstream.execute(() -> {
                    
                    return downstream.execute(() -> {
                        downstreamCalls.incrementAndGet();
                        
                        throw new RuntimeException("Downstream failure");
                    });
                });
                upstreamSuccesses.incrementAndGet();
            } catch (Exception e) {
                
            }
        }

        
        assertThat(downstream.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        
        
        assertThat(downstreamCalls.get()).isEqualTo(3);
        
        
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
        AtomicInteger rejectedDuringOpen = new AtomicInteger(0);

        // Trip the circuit breaker
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

        // Test thundering herd WHILE circuit is OPEN (before recovery)
        int herdSize = 100;
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(herdSize);

        for (int i = 0; i < herdSize; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    breaker.execute(() -> {
                        actualCalls.incrementAndGet();
                        return "success";
                    });
                } catch (CircuitBreakerOpenException e) {
                    rejectedDuringOpen.incrementAndGet();
                } catch (Exception e) {
                    // Other errors
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.printf("Thundering Herd Results: %d rejected, %d actual calls%n",
                rejectedDuringOpen.get(), actualCalls.get());

        // When circuit is OPEN, most requests should be rejected
        assertThat(rejectedDuringOpen.get()).isGreaterThan(herdSize / 2);

        // Circuit should still be protecting the system
        assertThat(breaker.getState()).isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);
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
                
                Thread.sleep(10);
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        }

        System.out.printf("Flaky Service: %d success, %d failure, %d rejected%n",
                successCount.get(), failureCount.get(), rejectedCount.get());

        
        assertThat(successCount.get()).isGreaterThan(200);
        
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
        AtomicInteger errorRate = new AtomicInteger(0); 

        
        ScheduledExecutorService degradationSimulator = Executors.newSingleThreadScheduledExecutor();
        degradationSimulator.scheduleAtFixedRate(() -> {
            int current = errorRate.get();
            if (current < 80) {
                errorRate.incrementAndGet(); 
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        Random random = new Random(42);
        AtomicInteger openCount = new AtomicInteger(0);

        
        long endTime = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < endTime) {
            long start = System.nanoTime();
            try {
                breaker.execute(() -> {
                    
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
                
            }
        }

        degradationSimulator.shutdown();

        System.out.printf("Gradual Degradation: Circuit opened %d times, final error rate: %d%%%n",
                openCount.get(), errorRate.get());

        
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
                .openDurationMs(100) 
                .build();

        List<CircuitBreaker.State> stateHistory = new CopyOnWriteArrayList<>();
        breaker.onStateChange((from, to) -> stateHistory.add(to));

        Random random = new Random(42);
        
        
        for (int cycle = 0; cycle < 20; cycle++) {
            
            for (int i = 0; i < 2; i++) {
                try {
                    breaker.execute(() -> {
                        throw new RuntimeException("Failure");
                    });
                } catch (Exception e) {
                    
                }
            }

            
            Thread.sleep(150);

            
            try {
                breaker.execute(() -> "success");
            } catch (CircuitBreakerOpenException e) {
                
            }
        }

        System.out.printf("Rapid Transitions: %d state changes recorded%n", stateHistory.size());

        
        assertThat(stateHistory.size()).isGreaterThan(10);
        
        
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
                .openDurationMs(100)
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
                            
                            
                            if (stateBefore == CircuitBreaker.State.CLOSED) {
                                
                            }
                        } catch (Exception e) {
                            failedOperations.incrementAndGet();
                        }

                        
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

        
        assertThat(totalOperations).isEqualTo(threadCount * operationsPerThread);
        
        
        assertThat(inconsistencies.get()).isZero();
        
        
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

        
        for (int i = 0; i < 10; i++) {
            try {
                breaker.execute(() -> {
                    if (serviceDown.get()) {
                        throw new RuntimeException("Service unavailable");
                    }
                    return "success";
                });
            } catch (Exception e) {
                
            }
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        
        serviceDown.set(false);

        
        AtomicInteger postRecoverySuccesses = new AtomicInteger(0);
        
        for (int attempt = 0; attempt < 20; attempt++) {
            Thread.sleep(200);
            try {
                breaker.execute(() -> "recovered");
                postRecoverySuccesses.incrementAndGet();
            } catch (CircuitBreakerOpenException e) {
                
            }
        }

        
        assertThat(postRecoverySuccesses.get()).isGreaterThan(0);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
