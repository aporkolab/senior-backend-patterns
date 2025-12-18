package com.aporkolab.patterns.bulkhead;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;


public interface Bulkhead {

    
    <T> T execute(Supplier<T> supplier) throws BulkheadFullException;

    
    void execute(Runnable runnable) throws BulkheadFullException;

    
    <T> Future<T> submit(Callable<T> callable) throws BulkheadFullException;

    
    Metrics getMetrics();

    
    String getName();

    static ThreadPoolBulkheadBuilder threadPool() {
        return new ThreadPoolBulkheadBuilder();
    }

    static SemaphoreBulkheadBuilder semaphore() {
        return new SemaphoreBulkheadBuilder();
    }

    
    interface Metrics {
        int getAvailableConcurrentCalls();
        int getMaxAllowedConcurrentCalls();
        int getCurrentConcurrentCalls();
        long getRejectedCalls();
    }

    

    class ThreadPoolBulkhead implements Bulkhead {
        private final String name;
        private final ExecutorService executor;
        private final int maxConcurrentCalls;
        private final Duration maxWaitDuration;
        private final AtomicInteger rejectedCalls = new AtomicInteger(0);

        ThreadPoolBulkhead(ThreadPoolBulkheadBuilder builder) {
            this.name = builder.name;
            this.maxConcurrentCalls = builder.maxConcurrentCalls;
            this.maxWaitDuration = builder.maxWaitDuration;

            
            java.util.concurrent.BlockingQueue<Runnable> workQueue = builder.queueCapacity == 0
                    ? new java.util.concurrent.SynchronousQueue<>()
                    : new LinkedBlockingQueue<>(builder.queueCapacity);

            this.executor = new ThreadPoolExecutor(
                    builder.corePoolSize,
                    builder.maxConcurrentCalls,
                    60L, TimeUnit.SECONDS,
                    workQueue,
                    r -> {
                        Thread t = new Thread(r, "bulkhead-" + name + "-" + System.nanoTime());
                        t.setDaemon(true);
                        return t;
                    },
                    (r, executor) -> {
                        rejectedCalls.incrementAndGet();
                        throw new RejectedExecutionException("Bulkhead " + name + " is full");
                    }
            );
        }

        @Override
        public <T> T execute(Supplier<T> supplier) throws BulkheadFullException {
            Future<T> future = null;
            try {
                future = executor.submit(supplier::get);
                return future.get(maxWaitDuration.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                throw new BulkheadFullException(name, maxConcurrentCalls);
            } catch (Exception e) {
                
                if (future != null) {
                    future.cancel(true);
                }
                throw new RuntimeException("Bulkhead execution failed", e);
            }
        }

        @Override
        public void execute(Runnable runnable) throws BulkheadFullException {
            execute(() -> { runnable.run(); return null; });
        }

        @Override
        public <T> Future<T> submit(Callable<T> callable) throws BulkheadFullException {
            try {
                return executor.submit(callable);
            } catch (RejectedExecutionException e) {
                throw new BulkheadFullException(name, maxConcurrentCalls);
            }
        }

        @Override
        public Metrics getMetrics() {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
            return new MetricsImpl(
                    maxConcurrentCalls - tpe.getActiveCount(),
                    maxConcurrentCalls,
                    tpe.getActiveCount(),
                    rejectedCalls.get()
            );
        }

        @Override
        public String getName() {
            return name;
        }

        public void shutdown() {
            executor.shutdown();
        }
    }

    

    class SemaphoreBulkhead implements Bulkhead {
        private final String name;
        private final Semaphore semaphore;
        private final int maxConcurrentCalls;
        private final Duration maxWaitDuration;
        private final AtomicInteger rejectedCalls = new AtomicInteger(0);
        private final AtomicInteger currentCalls = new AtomicInteger(0);

        SemaphoreBulkhead(SemaphoreBulkheadBuilder builder) {
            this.name = builder.name;
            this.maxConcurrentCalls = builder.maxConcurrentCalls;
            this.maxWaitDuration = builder.maxWaitDuration;
            this.semaphore = new Semaphore(maxConcurrentCalls, true);
        }

        @Override
        public <T> T execute(Supplier<T> supplier) throws BulkheadFullException {
            boolean acquired = false;
            try {
                acquired = semaphore.tryAcquire(maxWaitDuration.toMillis(), TimeUnit.MILLISECONDS);
                if (!acquired) {
                    rejectedCalls.incrementAndGet();
                    throw new BulkheadFullException(name, maxConcurrentCalls);
                }
                currentCalls.incrementAndGet();
                return supplier.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for bulkhead", e);
            } finally {
                if (acquired) {
                    currentCalls.decrementAndGet();
                    semaphore.release();
                }
            }
        }

        @Override
        public void execute(Runnable runnable) throws BulkheadFullException {
            execute(() -> { runnable.run(); return null; });
        }

        @Override
        public <T> Future<T> submit(Callable<T> callable) throws BulkheadFullException {
            throw new UnsupportedOperationException("Semaphore bulkhead does not support async submission");
        }

        @Override
        public Metrics getMetrics() {
            return new MetricsImpl(
                    semaphore.availablePermits(),
                    maxConcurrentCalls,
                    currentCalls.get(),
                    rejectedCalls.get()
            );
        }

        @Override
        public String getName() {
            return name;
        }
    }

    

    class ThreadPoolBulkheadBuilder {
        String name = "default";
        int maxConcurrentCalls = 10;
        int corePoolSize = 5;
        int queueCapacity = 100;
        Duration maxWaitDuration = Duration.ofMillis(500);

        public ThreadPoolBulkheadBuilder name(String name) {
            this.name = Objects.requireNonNull(name);
            return this;
        }

        public ThreadPoolBulkheadBuilder maxConcurrentCalls(int maxConcurrentCalls) {
            if (maxConcurrentCalls <= 0) throw new IllegalArgumentException("Must be positive");
            this.maxConcurrentCalls = maxConcurrentCalls;
            return this;
        }

        public ThreadPoolBulkheadBuilder corePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
            return this;
        }

        public ThreadPoolBulkheadBuilder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public ThreadPoolBulkheadBuilder maxWaitDuration(Duration maxWaitDuration) {
            this.maxWaitDuration = Objects.requireNonNull(maxWaitDuration);
            return this;
        }

        public Bulkhead build() {
            return new ThreadPoolBulkhead(this);
        }
    }

    class SemaphoreBulkheadBuilder {
        String name = "default";
        int maxConcurrentCalls = 10;
        Duration maxWaitDuration = Duration.ofMillis(500);

        public SemaphoreBulkheadBuilder name(String name) {
            this.name = Objects.requireNonNull(name);
            return this;
        }

        public SemaphoreBulkheadBuilder maxConcurrentCalls(int maxConcurrentCalls) {
            if (maxConcurrentCalls <= 0) throw new IllegalArgumentException("Must be positive");
            this.maxConcurrentCalls = maxConcurrentCalls;
            return this;
        }

        public SemaphoreBulkheadBuilder maxWaitDuration(Duration maxWaitDuration) {
            this.maxWaitDuration = Objects.requireNonNull(maxWaitDuration);
            return this;
        }

        public Bulkhead build() {
            return new SemaphoreBulkhead(this);
        }
    }

    

    record MetricsImpl(
            int availableConcurrentCalls,
            int maxAllowedConcurrentCalls,
            int currentConcurrentCalls,
            long rejectedCalls
    ) implements Metrics {
        @Override public int getAvailableConcurrentCalls() { return availableConcurrentCalls; }
        @Override public int getMaxAllowedConcurrentCalls() { return maxAllowedConcurrentCalls; }
        @Override public int getCurrentConcurrentCalls() { return currentConcurrentCalls; }
        @Override public long getRejectedCalls() { return rejectedCalls; }
    }
}
