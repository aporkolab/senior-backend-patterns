package com.aporkolab.patterns.async;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for common async patterns with CompletableFuture.
 * 
 * Design decisions:
 * - Uses virtual threads by default (Java 21+)
 * - Provides timeout handling (critical for production)
 * - Supports parallel execution with controlled concurrency
 * - Includes structured error handling patterns
 */
public class AsyncPipeline {

    private static final Logger log = LoggerFactory.getLogger(AsyncPipeline.class);

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    /**
     * Create pipeline with virtual thread executor (Java 21+).
     */
    public AsyncPipeline() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Create pipeline with custom executor.
     */
    public AsyncPipeline(ExecutorService executor) {
        this.executor = executor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Run a supplier asynchronously.
     */
    public <T> CompletableFuture<T> async(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    /**
     * Run multiple suppliers in parallel, return when all complete.
     */
    @SafeVarargs
    public final <T> CompletableFuture<List<T>> parallel(Supplier<T>... suppliers) {
        List<CompletableFuture<T>> futures = java.util.Arrays.stream(suppliers)
                .map(s -> CompletableFuture.supplyAsync(s, executor))
                .toList();

        return allOf(futures);
    }

    /**
     * Combine multiple futures into a list of results.
     */
    public <T> CompletableFuture<List<T>> allOf(List<CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }

    /**
     * Return the first successful result (racing pattern).
     */
    public <T> CompletableFuture<T> race(List<CompletableFuture<T>> futures) {
        CompletableFuture<T> result = new CompletableFuture<>();

        futures.forEach(future -> future
                .thenAccept(result::complete)
                .exceptionally(ex -> null) // Ignore failures
        );

        return result;
    }

    /**
     * Add timeout to a future.
     * Critical for production - async without timeout = memory leak.
     */
    public <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, Duration timeout) {
        CompletableFuture<T> timeoutFuture = new CompletableFuture<>();

        scheduler.schedule(
                () -> timeoutFuture.completeExceptionally(
                        new TimeoutException("Operation timed out after " + timeout)),
                timeout.toMillis(),
                TimeUnit.MILLISECONDS
        );

        return future.applyToEither(timeoutFuture, Function.identity());
    }

    /**
     * Retry an async operation with exponential backoff.
     */
    public <T> CompletableFuture<T> withRetry(
            Supplier<CompletableFuture<T>> operation,
            int maxRetries,
            Duration initialDelay) {

        return retryInternal(operation, maxRetries, initialDelay, 0);
    }

    private <T> CompletableFuture<T> retryInternal(
            Supplier<CompletableFuture<T>> operation,
            int maxRetries,
            Duration delay,
            int attempt) {

        return operation.get()
                .exceptionallyCompose(ex -> {
                    if (attempt >= maxRetries) {
                        log.warn("Max retries ({}) exceeded, giving up", maxRetries);
                        return CompletableFuture.failedFuture(ex);
                    }

                    long delayMs = delay.toMillis() * (1L << attempt); // Exponential
                    log.info("Retry {}/{} after {}ms: {}", attempt + 1, maxRetries, delayMs, ex.getMessage());

                    return delay(Duration.ofMillis(delayMs))
                            .thenCompose(v -> retryInternal(operation, maxRetries, delay, attempt + 1));
                });
    }

    /**
     * Create a delayed future.
     */
    public CompletableFuture<Void> delay(Duration duration) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.schedule(() -> future.complete(null), duration.toMillis(), TimeUnit.MILLISECONDS);
        return future;
    }

    /**
     * Fan-out pattern: apply operation to all items in parallel.
     */
    public <T, R> CompletableFuture<List<R>> fanOut(
            List<T> items,
            Function<T, CompletableFuture<R>> operation) {

        List<CompletableFuture<R>> futures = items.stream()
                .map(operation)
                .toList();

        return allOf(futures);
    }

    /**
     * Fan-out with controlled concurrency (limit parallel operations).
     */
    public <T, R> CompletableFuture<List<R>> fanOutLimited(
            List<T> items,
            Function<T, CompletableFuture<R>> operation,
            int maxConcurrency) {

        // Using semaphore pattern for concurrency control
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(maxConcurrency);

        List<CompletableFuture<R>> futures = items.stream()
                .map(item -> CompletableFuture.runAsync(() -> {
                            try {
                                semaphore.acquire();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }, executor)
                        .thenCompose(v -> operation.apply(item))
                        .whenComplete((r, ex) -> semaphore.release()))
                .toList();

        return allOf(futures);
    }

    /**
     * Shutdown the executor gracefully.
     */
    public void shutdown() {
        executor.shutdown();
        scheduler.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
