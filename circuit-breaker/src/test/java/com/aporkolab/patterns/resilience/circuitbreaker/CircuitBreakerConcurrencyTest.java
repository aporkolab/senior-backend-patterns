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
                    startLatch.await(); 
                    cb.execute(() -> {
                        throw new RuntimeException("concurrent failure");
                    });
                } catch (CircuitBreakerOpenException e) {
                    
                    exceptionCount.incrementAndGet();
                } catch (Exception ignored) {
                    
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); 
        endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        
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
                        
                        circuitBreaker.execute(() -> {
                            throw new RuntimeException("failure");
                        });
                    } else {
                        circuitBreaker.execute(() -> "success");
                    }
                } catch (Exception ignored) {
                    
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should correctly transition through half-open with concurrent requests")
    void shouldTransitionCorrectlyUnderConcurrency() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.builder()
                .name("transition-test")
                .failureThreshold(5)
                .successThreshold(3)
                .openDurationMs(100)
                .build();

        
        for (int i = 0; i < 5; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        
        Thread.sleep(100);

        
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
                .openDurationMs(100)
                .build();

        cb.onStateChange((from, to) -> listenerCallCount.incrementAndGet());

        
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

        
        assertThat(listenerCallCount.get()).isGreaterThan(0);
    }
}
