package com.aporkolab.patterns.bulkhead;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BulkheadTest {

    @Nested
    @DisplayName("Semaphore Bulkhead")
    class SemaphoreBulkheadTests {

        @Test
        @DisplayName("should allow calls within limit")
        void shouldAllowCallsWithinLimit() {
            Bulkhead bulkhead = Bulkhead.semaphore()
                    .name("test")
                    .maxConcurrentCalls(5)
                    .build();

            String result = bulkhead.execute(() -> "success");
            
            assertEquals("success", result);
        }

        @Test
        @DisplayName("should reject calls when full")
        void shouldRejectWhenFull() throws Exception {
            Bulkhead bulkhead = Bulkhead.semaphore()
                    .name("test")
                    .maxConcurrentCalls(2)
                    .maxWaitDuration(Duration.ofMillis(50))
                    .build();

            CountDownLatch blockedLatch = new CountDownLatch(2);
            CountDownLatch releaseLatch = new CountDownLatch(1);
            AtomicInteger rejected = new AtomicInteger(0);

            // Fill the bulkhead
            ExecutorService executor = Executors.newFixedThreadPool(3);
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    bulkhead.execute(() -> {
                        blockedLatch.countDown();
                        try {
                            releaseLatch.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                });
            }

            blockedLatch.await(1, TimeUnit.SECONDS);

            // Try to acquire when full
            try {
                bulkhead.execute(() -> "should fail");
                fail("Should have thrown BulkheadFullException");
            } catch (BulkheadFullException e) {
                rejected.incrementAndGet();
            }

            assertEquals(1, rejected.get());
            
            releaseLatch.countDown();
            executor.shutdown();
        }

        @Test
        @DisplayName("should track metrics correctly")
        void shouldTrackMetrics() {
            Bulkhead bulkhead = Bulkhead.semaphore()
                    .name("metrics-test")
                    .maxConcurrentCalls(10)
                    .build();

            Bulkhead.Metrics metrics = bulkhead.getMetrics();
            
            assertEquals(10, metrics.getMaxAllowedConcurrentCalls());
            assertEquals(10, metrics.getAvailableConcurrentCalls());
            assertEquals(0, metrics.getCurrentConcurrentCalls());
        }
    }

    @Nested
    @DisplayName("ThreadPool Bulkhead")
    class ThreadPoolBulkheadTests {

        @Test
        @DisplayName("should execute in separate thread pool")
        void shouldExecuteInSeparateThreadPool() {
            Bulkhead bulkhead = Bulkhead.threadPool()
                    .name("separate-pool")
                    .maxConcurrentCalls(5)
                    .build();

            String mainThread = Thread.currentThread().getName();
            String[] executionThread = new String[1];

            bulkhead.execute(() -> {
                executionThread[0] = Thread.currentThread().getName();
                return null;
            });

            assertNotEquals(mainThread, executionThread[0]);
            assertTrue(executionThread[0].contains("bulkhead-separate-pool"));
        }

        @Test
        @DisplayName("should limit concurrent executions")
        void shouldLimitConcurrentExecutions() throws Exception {
            Bulkhead bulkhead = Bulkhead.threadPool()
                    .name("concurrent-test")
                    .maxConcurrentCalls(3)
                    .queueCapacity(0) // No queue - reject immediately
                    .maxWaitDuration(Duration.ofSeconds(5))
                    .build();

            AtomicInteger maxConcurrent = new AtomicInteger(0);
            AtomicInteger currentConcurrent = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(3);
            CountDownLatch endLatch = new CountDownLatch(1);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (int i = 0; i < 3; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    bulkhead.execute(() -> {
                        int current = currentConcurrent.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));
                        startLatch.countDown();
                        try {
                            endLatch.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        currentConcurrent.decrementAndGet();
                        return null;
                    });
                }));
            }

            startLatch.await(2, TimeUnit.SECONDS);
            
            // All 3 should be running
            assertEquals(3, currentConcurrent.get());
            
            endLatch.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            assertEquals(3, maxConcurrent.get());
        }

        @Test
        @DisplayName("should increment rejected counter on overflow")
        void shouldTrackRejectedCalls() throws Exception {
            Bulkhead bulkhead = Bulkhead.threadPool()
                    .name("reject-test")
                    .maxConcurrentCalls(1)
                    .queueCapacity(0)
                    .maxWaitDuration(Duration.ofSeconds(1))
                    .build();

            CountDownLatch blockLatch = new CountDownLatch(1);
            CountDownLatch releaseLatch = new CountDownLatch(1);

            // Block the single thread
            CompletableFuture.runAsync(() -> {
                bulkhead.execute(() -> {
                    blockLatch.countDown();
                    try {
                        releaseLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            });

            blockLatch.await(1, TimeUnit.SECONDS);

            // Should be rejected
            try {
                bulkhead.execute(() -> "should fail");
                fail("Expected BulkheadFullException");
            } catch (BulkheadFullException e) {
                // Expected
            }

            assertEquals(1, bulkhead.getMetrics().getRejectedCalls());
            
            releaseLatch.countDown();
        }
    }

    @Test
    @DisplayName("should handle exceptions in supplier")
    void shouldPropagateExceptions() {
        Bulkhead bulkhead = Bulkhead.semaphore()
                .name("exception-test")
                .maxConcurrentCalls(5)
                .build();

        assertThrows(RuntimeException.class, () -> {
            bulkhead.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
    }

    @Test
    @DisplayName("should release permit after exception")
    void shouldReleasePermitAfterException() {
        Bulkhead bulkhead = Bulkhead.semaphore()
                .name("release-test")
                .maxConcurrentCalls(1)
                .build();

        // First call throws
        try {
            bulkhead.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        // Second call should succeed (permit was released)
        String result = bulkhead.execute(() -> "success");
        assertEquals("success", result);
    }
}
