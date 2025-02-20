package com.aporkolab.patterns.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for AsyncPipeline utilities.
 */
class AsyncPipelineTest {

    private AsyncPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new AsyncPipeline();
    }

    @AfterEach
    void tearDown() {
        pipeline.shutdown();
    }

    @Nested
    @DisplayName("Basic Async Execution")
    class BasicAsyncExecution {

        @Test
        @DisplayName("should execute supplier asynchronously")
        void shouldExecuteAsync() throws Exception {
            CompletableFuture<String> future = pipeline.async(() -> "result");

            String result = future.get();

            assertThat(result).isEqualTo("result");
        }

        @Test
        @DisplayName("should propagate exception from supplier")
        void shouldPropagateException() {
            CompletableFuture<String> future = pipeline.async(() -> {
                throw new RuntimeException("async error");
            });

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("async error");
        }

        @Test
        @DisplayName("should execute on different thread")
        void shouldExecuteOnDifferentThread() throws Exception {
            Thread mainThread = Thread.currentThread();

            CompletableFuture<Thread> future = pipeline.async(Thread::currentThread);
            Thread asyncThread = future.get();

            assertThat(asyncThread).isNotSameAs(mainThread);
        }
    }

    @Nested
    @DisplayName("Parallel Execution")
    class ParallelExecution {

        @Test
        @DisplayName("should execute multiple suppliers in parallel")
        void shouldExecuteInParallel() throws Exception {
            long start = System.currentTimeMillis();

            CompletableFuture<List<String>> future = pipeline.parallel(
                    () -> { sleep(100); return "a"; },
                    () -> { sleep(100); return "b"; },
                    () -> { sleep(100); return "c"; }
            );

            List<String> results = future.get();
            long duration = System.currentTimeMillis() - start;

            assertThat(results).containsExactlyInAnyOrder("a", "b", "c");
            // Should take ~100ms if parallel, ~300ms if sequential
            assertThat(duration).isLessThan(250);
        }

        @Test
        @DisplayName("should combine futures with allOf")
        void shouldCombineFutures() throws Exception {
            List<CompletableFuture<Integer>> futures = List.of(
                    pipeline.async(() -> 1),
                    pipeline.async(() -> 2),
                    pipeline.async(() -> 3)
            );

            CompletableFuture<List<Integer>> combined = pipeline.allOf(futures);
            List<Integer> results = combined.get();

            assertThat(results).containsExactlyInAnyOrder(1, 2, 3);
        }

        @Test
        @DisplayName("should fail allOf if any future fails")
        void shouldFailAllOfOnAnyFailure() {
            List<CompletableFuture<String>> futures = List.of(
                    pipeline.async(() -> "ok"),
                    pipeline.async(() -> { throw new RuntimeException("fail"); }),
                    pipeline.async(() -> "ok2")
            );

            CompletableFuture<List<String>> combined = pipeline.allOf(futures);

            assertThatThrownBy(combined::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Race Pattern")
    class RacePattern {

        @Test
        @DisplayName("should return first successful result")
        void shouldReturnFirstResult() throws Exception {
            List<CompletableFuture<String>> futures = List.of(
                    pipeline.async(() -> { sleep(200); return "slow"; }),
                    pipeline.async(() -> { sleep(50); return "fast"; }),
                    pipeline.async(() -> { sleep(100); return "medium"; })
            );

            CompletableFuture<String> race = pipeline.race(futures);
            String result = race.get();

            assertThat(result).isEqualTo("fast");
        }

        @Test
        @DisplayName("should ignore failures if one succeeds")
        void shouldIgnoreFailures() throws Exception {
            List<CompletableFuture<String>> futures = List.of(
                    pipeline.async(() -> { throw new RuntimeException("fail1"); }),
                    pipeline.async(() -> { sleep(50); return "success"; }),
                    pipeline.async(() -> { throw new RuntimeException("fail2"); })
            );

            CompletableFuture<String> race = pipeline.race(futures);
            String result = race.get();

            assertThat(result).isEqualTo("success");
        }
    }

    @Nested
    @DisplayName("Timeout Handling")
    class TimeoutHandling {

        @Test
        @DisplayName("should complete before timeout")
        void shouldCompleteBeforeTimeout() throws Exception {
            CompletableFuture<String> fast = pipeline.async(() -> "quick");
            CompletableFuture<String> withTimeout = pipeline.withTimeout(fast, Duration.ofSeconds(1));

            String result = withTimeout.get();

            assertThat(result).isEqualTo("quick");
        }

        @Test
        @DisplayName("should timeout slow operations")
        void shouldTimeoutSlowOperations() {
            CompletableFuture<String> slow = pipeline.async(() -> {
                sleep(5000);
                return "too late";
            });

            CompletableFuture<String> withTimeout = pipeline.withTimeout(slow, Duration.ofMillis(100));

            assertThatThrownBy(withTimeout::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TimeoutException.class)
                    .hasMessageContaining("timed out");
        }
    }

    @Nested
    @DisplayName("Retry with Backoff")
    class RetryWithBackoff {

        @Test
        @DisplayName("should succeed on first attempt if no failure")
        void shouldSucceedOnFirstAttempt() throws Exception {
            AtomicInteger attempts = new AtomicInteger(0);

            CompletableFuture<String> future = pipeline.withRetry(
                    () -> pipeline.async(() -> {
                        attempts.incrementAndGet();
                        return "success";
                    }),
                    3,
                    Duration.ofMillis(10)
            );

            String result = future.get();

            assertThat(result).isEqualTo("success");
            assertThat(attempts.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should retry on failure")
        void shouldRetryOnFailure() throws Exception {
            AtomicInteger attempts = new AtomicInteger(0);

            CompletableFuture<String> future = pipeline.withRetry(
                    () -> pipeline.async(() -> {
                        int attempt = attempts.incrementAndGet();
                        if (attempt < 3) {
                            throw new RuntimeException("fail " + attempt);
                        }
                        return "success on " + attempt;
                    }),
                    5,
                    Duration.ofMillis(10)
            );

            String result = future.get();

            assertThat(result).isEqualTo("success on 3");
            assertThat(attempts.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("should fail after max retries")
        void shouldFailAfterMaxRetries() {
            AtomicInteger attempts = new AtomicInteger(0);

            CompletableFuture<String> future = pipeline.withRetry(
                    () -> pipeline.async(() -> {
                        attempts.incrementAndGet();
                        throw new RuntimeException("always fail");
                    }),
                    3,
                    Duration.ofMillis(10)
            );

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);

            // 1 initial + 3 retries = 4 attempts
            assertThat(attempts.get()).isEqualTo(4);
        }

        @Test
        @DisplayName("should apply exponential backoff")
        void shouldApplyExponentialBackoff() throws Exception {
            AtomicInteger attempts = new AtomicInteger(0);
            long[] timestamps = new long[4];

            CompletableFuture<String> future = pipeline.withRetry(
                    () -> pipeline.async(() -> {
                        int attempt = attempts.getAndIncrement();
                        timestamps[attempt] = System.currentTimeMillis();
                        if (attempt < 3) {
                            throw new RuntimeException("fail");
                        }
                        return "done";
                    }),
                    3,
                    Duration.ofMillis(50)
            );

            future.get();

            // Verify delays are increasing (exponential)
            // Delay 1: ~50ms, Delay 2: ~100ms, Delay 3: ~200ms
            long delay1 = timestamps[1] - timestamps[0];
            long delay2 = timestamps[2] - timestamps[1];
            long delay3 = timestamps[3] - timestamps[2];

            assertThat(delay2).isGreaterThan(delay1);
            assertThat(delay3).isGreaterThan(delay2);
        }
    }

    @Nested
    @DisplayName("Fan-Out Pattern")
    class FanOutPattern {

        @Test
        @DisplayName("should apply operation to all items in parallel")
        void shouldFanOutToAllItems() throws Exception {
            List<Integer> items = List.of(1, 2, 3, 4, 5);

            CompletableFuture<List<Integer>> future = pipeline.fanOut(
                    items,
                    item -> pipeline.async(() -> item * 2)
            );

            List<Integer> results = future.get();

            assertThat(results).containsExactlyInAnyOrder(2, 4, 6, 8, 10);
        }

        @Test
        @DisplayName("should respect concurrency limit in fanOutLimited")
        void shouldRespectConcurrencyLimit() throws Exception {
            AtomicInteger concurrent = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);

            List<Integer> items = List.of(1, 2, 3, 4, 5, 6, 7, 8);

            CompletableFuture<List<Integer>> future = pipeline.fanOutLimited(
                    items,
                    item -> pipeline.async(() -> {
                        int current = concurrent.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));
                        sleep(50);
                        concurrent.decrementAndGet();
                        return item;
                    }),
                    3 // max 3 concurrent
            );

            future.get();

            assertThat(maxConcurrent.get()).isLessThanOrEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Delay")
    class DelayTest {

        @Test
        @DisplayName("should delay for specified duration")
        void shouldDelayForDuration() throws Exception {
            long start = System.currentTimeMillis();

            pipeline.delay(Duration.ofMillis(100)).get();

            long duration = System.currentTimeMillis() - start;
            assertThat(duration).isGreaterThanOrEqualTo(90); // Allow some tolerance
        }

        @Test
        @DisplayName("should complete delay future")
        void shouldCompleteDelayFuture() throws Exception {
            CompletableFuture<Void> delay = pipeline.delay(Duration.ofMillis(10));

            delay.get(); // Should not throw

            assertThat(delay.isDone()).isTrue();
        }
    }

    @Nested
    @DisplayName("Shutdown")
    class ShutdownTest {

        @Test
        @DisplayName("should shutdown gracefully")
        void shouldShutdownGracefully() {
            AsyncPipeline localPipeline = new AsyncPipeline();
            
            // Submit some work
            localPipeline.async(() -> "test");
            
            // Shutdown should not throw
            localPipeline.shutdown();
        }
    }

    // Helper method
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
