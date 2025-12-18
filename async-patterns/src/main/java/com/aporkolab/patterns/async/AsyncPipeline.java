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


public class AsyncPipeline {

    private static final Logger log = LoggerFactory.getLogger(AsyncPipeline.class);

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    
    public AsyncPipeline() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    
    public AsyncPipeline(ExecutorService executor) {
        this.executor = executor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    
    public <T> CompletableFuture<T> async(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    
    @SafeVarargs
    public final <T> CompletableFuture<List<T>> parallel(Supplier<T>... suppliers) {
        List<CompletableFuture<T>> futures = java.util.Arrays.stream(suppliers)
                .map(s -> CompletableFuture.supplyAsync(s, executor))
                .toList();

        return allOf(futures);
    }

    
    public <T> CompletableFuture<List<T>> allOf(List<CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }

    
    public <T> CompletableFuture<T> race(List<CompletableFuture<T>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Cannot race with empty list of futures"));
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<Throwable> lastFailure = new java.util.concurrent.atomic.AtomicReference<>();

        futures.forEach(future -> future
                .thenAccept(result::complete)
                .exceptionally(ex -> {
                    lastFailure.set(ex);
                    if (failureCount.incrementAndGet() == futures.size()) {
                        
                        result.completeExceptionally(new RuntimeException(
                                "All " + futures.size() + " futures in race failed", lastFailure.get()));
                    }
                    return null;
                })
        );

        return result;
    }

    
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

                    long delayMs = delay.toMillis() * (1L << attempt); 
                    log.info("Retry {}/{} after {}ms: {}", attempt + 1, maxRetries, delayMs, ex.getMessage());

                    return delay(Duration.ofMillis(delayMs))
                            .thenCompose(v -> retryInternal(operation, maxRetries, delay, attempt + 1));
                });
    }

    
    public CompletableFuture<Void> delay(Duration duration) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.schedule(() -> future.complete(null), duration.toMillis(), TimeUnit.MILLISECONDS);
        return future;
    }

    
    public <T, R> CompletableFuture<List<R>> fanOut(
            List<T> items,
            Function<T, CompletableFuture<R>> operation) {

        List<CompletableFuture<R>> futures = items.stream()
                .map(operation)
                .toList();

        return allOf(futures);
    }

    
    public <T, R> CompletableFuture<List<R>> fanOutLimited(
            List<T> items,
            Function<T, CompletableFuture<R>> operation,
            int maxConcurrency) {

        
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(maxConcurrency);

        List<CompletableFuture<R>> futures = items.stream()
                .map(item -> {
                    
                    java.util.concurrent.atomic.AtomicBoolean acquired = new java.util.concurrent.atomic.AtomicBoolean(false);
                    return CompletableFuture.runAsync(() -> {
                                try {
                                    semaphore.acquire();
                                    acquired.set(true);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException("Interrupted while acquiring semaphore", e);
                                }
                            }, executor)
                            .thenCompose(v -> operation.apply(item))
                            .whenComplete((r, ex) -> {
                                if (acquired.get()) {
                                    semaphore.release();
                                }
                            });
                })
                .toList();

        return allOf(futures);
    }

    
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
