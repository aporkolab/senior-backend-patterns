package com.aporkolab.patterns.ratelimiter;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Production-grade Rate Limiter with multiple algorithm support.
 * 
 * Supports:
 * - Token Bucket (smooth rate limiting with burst support)
 * - Sliding Window (precise rate limiting)
 * - Fixed Window (simple, memory efficient)
 * 
 * Features:
 * - Thread-safe (lock-free where possible)
 * - Per-key rate limiting
 * - Configurable rejection handling
 * - Metrics-ready
 * 
 * Usage:
 * <pre>{@code
 * RateLimiter limiter = RateLimiter.tokenBucket()
 *     .capacity(100)
 *     .refillRate(10)
 *     .refillPeriod(Duration.ofSeconds(1))
 *     .build();
 * 
 * if (limiter.tryAcquire("user-123")) {
 *     // Process request
 * } else {
 *     // Reject or queue
 * }
 * }</pre>
 */
public interface RateLimiter {

    /**
     * Try to acquire a permit for the given key.
     * 
     * @param key the rate limit key (e.g., user ID, IP address)
     * @return true if permit was acquired, false if rate limited
     */
    boolean tryAcquire(String key);

    /**
     * Try to acquire multiple permits.
     */
    boolean tryAcquire(String key, int permits);

    /**
     * Acquire a permit, blocking if necessary.
     * 
     * @param key the rate limit key
     * @param timeout maximum time to wait
     * @return true if permit was acquired within timeout
     */
    boolean acquire(String key, Duration timeout) throws InterruptedException;

    /**
     * Get remaining permits for a key.
     */
    long getRemainingPermits(String key);

    /**
     * Get time until next permit is available.
     */
    Optional<Duration> getTimeUntilNextPermit(String key);

    /**
     * Execute with rate limiting.
     */
    default <T> Optional<T> executeIfAllowed(String key, Supplier<T> action) {
        if (tryAcquire(key)) {
            return Optional.ofNullable(action.get());
        }
        return Optional.empty();
    }

    /**
     * Get the rate limiter name.
     */
    String getName();

    /**
     * Get the algorithm type.
     */
    Algorithm getAlgorithm();

    /**
     * Reset rate limit state for a key.
     */
    void reset(String key);

    /**
     * Reset all rate limit state.
     */
    void resetAll();

    // Builder methods
    static TokenBucketBuilder tokenBucket() {
        return new TokenBucketBuilder();
    }

    static SlidingWindowBuilder slidingWindow() {
        return new SlidingWindowBuilder();
    }

    static FixedWindowBuilder fixedWindow() {
        return new FixedWindowBuilder();
    }

    enum Algorithm {
        TOKEN_BUCKET,
        SLIDING_WINDOW,
        FIXED_WINDOW
    }

    // ==================== TOKEN BUCKET IMPLEMENTATION ====================

    /**
     * Token Bucket algorithm implementation.
     * 
     * - Allows bursts up to bucket capacity
     * - Tokens refill at a constant rate
     * - Smooth rate limiting over time
     */
    class TokenBucket implements RateLimiter {

        private final String name;
        private final long capacity;
        private final long refillTokens;
        private final Duration refillPeriod;
        private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

        TokenBucket(String name, long capacity, long refillTokens, Duration refillPeriod) {
            this.name = name;
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.refillPeriod = refillPeriod;
        }

        @Override
        public boolean tryAcquire(String key) {
            return tryAcquire(key, 1);
        }

        @Override
        public boolean tryAcquire(String key, int permits) {
            Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
            return bucket.tryConsume(permits, refillTokens, refillPeriod);
        }

        @Override
        public boolean acquire(String key, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            
            while (System.nanoTime() < deadline) {
                if (tryAcquire(key)) {
                    return true;
                }
                Thread.sleep(Math.min(10, timeout.toMillis() / 10));
            }
            return false;
        }

        @Override
        public long getRemainingPermits(String key) {
            Bucket bucket = buckets.get(key);
            if (bucket == null) return capacity;
            return bucket.getAvailableTokens(refillTokens, refillPeriod);
        }

        @Override
        public Optional<Duration> getTimeUntilNextPermit(String key) {
            Bucket bucket = buckets.get(key);
            if (bucket == null) return Optional.empty();
            if (bucket.getAvailableTokens(refillTokens, refillPeriod) > 0) {
                return Optional.empty();
            }
            // Calculate time for next refill
            long timeSinceLastRefill = System.nanoTime() - bucket.lastRefillTime.get();
            long refillPeriodNanos = refillPeriod.toNanos();
            long waitTime = refillPeriodNanos - (timeSinceLastRefill % refillPeriodNanos);
            return Optional.of(Duration.ofNanos(waitTime));
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Algorithm getAlgorithm() {
            return Algorithm.TOKEN_BUCKET;
        }

        @Override
        public void reset(String key) {
            buckets.remove(key);
        }

        @Override
        public void resetAll() {
            buckets.clear();
        }

        private class Bucket {
            private final AtomicLong tokens;
            private final AtomicLong lastRefillTime;
            private final long maxTokens;

            Bucket(long maxTokens) {
                this.maxTokens = maxTokens;
                this.tokens = new AtomicLong(maxTokens);
                this.lastRefillTime = new AtomicLong(System.nanoTime());
            }

            boolean tryConsume(int permits, long refillTokens, Duration refillPeriod) {
                refill(refillTokens, refillPeriod);
                
                while (true) {
                    long current = tokens.get();
                    if (current < permits) {
                        return false;
                    }
                    if (tokens.compareAndSet(current, current - permits)) {
                        return true;
                    }
                }
            }

            long getAvailableTokens(long refillTokens, Duration refillPeriod) {
                refill(refillTokens, refillPeriod);
                return tokens.get();
            }

            private void refill(long refillTokens, Duration refillPeriod) {
                long now = System.nanoTime();
                long lastRefill = lastRefillTime.get();
                long elapsed = now - lastRefill;
                long periodNanos = refillPeriod.toNanos();

                if (elapsed >= periodNanos) {
                    long periods = elapsed / periodNanos;
                    long tokensToAdd = periods * refillTokens;

                    if (lastRefillTime.compareAndSet(lastRefill, lastRefill + (periods * periodNanos))) {
                        long current = tokens.get();
                        long newTokens = Math.min(maxTokens, current + tokensToAdd);
                        tokens.set(newTokens);
                    }
                }
            }
        }
    }

    // ==================== SLIDING WINDOW IMPLEMENTATION ====================

    /**
     * Sliding Window algorithm implementation.
     * 
     * - Precise rate limiting
     * - No burst allowance
     * - Higher memory usage for tracking
     */
    class SlidingWindow implements RateLimiter {

        private final String name;
        private final long maxRequests;
        private final Duration windowSize;
        private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

        SlidingWindow(String name, long maxRequests, Duration windowSize) {
            this.name = name;
            this.maxRequests = maxRequests;
            this.windowSize = windowSize;
        }

        @Override
        public boolean tryAcquire(String key) {
            return tryAcquire(key, 1);
        }

        @Override
        public boolean tryAcquire(String key, int permits) {
            Window window = windows.computeIfAbsent(key, k -> new Window());
            return window.tryAcquire(permits, maxRequests, windowSize);
        }

        @Override
        public boolean acquire(String key, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            
            while (System.nanoTime() < deadline) {
                if (tryAcquire(key)) {
                    return true;
                }
                Thread.sleep(Math.min(10, timeout.toMillis() / 10));
            }
            return false;
        }

        @Override
        public long getRemainingPermits(String key) {
            Window window = windows.get(key);
            if (window == null) return maxRequests;
            return window.getRemainingPermits(maxRequests, windowSize);
        }

        @Override
        public Optional<Duration> getTimeUntilNextPermit(String key) {
            Window window = windows.get(key);
            if (window == null) return Optional.empty();
            return window.getTimeUntilNextPermit(windowSize);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Algorithm getAlgorithm() {
            return Algorithm.SLIDING_WINDOW;
        }

        @Override
        public void reset(String key) {
            windows.remove(key);
        }

        @Override
        public void resetAll() {
            windows.clear();
        }

        private class Window {
            private final ConcurrentMap<Long, AtomicLong> slots = new ConcurrentHashMap<>();
            private static final int SLOT_COUNT = 10;

            boolean tryAcquire(int permits, long maxRequests, Duration windowSize) {
                cleanup(windowSize);
                
                long currentCount = getCurrentCount(windowSize);
                if (currentCount + permits > maxRequests) {
                    return false;
                }

                long slotKey = getSlotKey(windowSize);
                slots.computeIfAbsent(slotKey, k -> new AtomicLong(0)).addAndGet(permits);
                return true;
            }

            long getRemainingPermits(long maxRequests, Duration windowSize) {
                cleanup(windowSize);
                return Math.max(0, maxRequests - getCurrentCount(windowSize));
            }

            Optional<Duration> getTimeUntilNextPermit(Duration windowSize) {
                long oldestSlot = slots.keySet().stream().min(Long::compare).orElse(0L);
                if (oldestSlot == 0) return Optional.empty();
                
                long slotDuration = windowSize.toMillis() / SLOT_COUNT;
                long now = System.currentTimeMillis();
                long expiryTime = oldestSlot * slotDuration + windowSize.toMillis();
                
                if (expiryTime > now) {
                    return Optional.of(Duration.ofMillis(expiryTime - now));
                }
                return Optional.empty();
            }

            private long getCurrentCount(Duration windowSize) {
                return slots.values().stream().mapToLong(AtomicLong::get).sum();
            }

            private long getSlotKey(Duration windowSize) {
                long slotDuration = windowSize.toMillis() / SLOT_COUNT;
                return System.currentTimeMillis() / slotDuration;
            }

            private void cleanup(Duration windowSize) {
                long slotDuration = windowSize.toMillis() / SLOT_COUNT;
                long cutoff = (System.currentTimeMillis() / slotDuration) - SLOT_COUNT;
                slots.keySet().removeIf(k -> k < cutoff);
            }
        }
    }

    // ==================== FIXED WINDOW IMPLEMENTATION ====================

    /**
     * Fixed Window algorithm implementation.
     * 
     * - Simple and memory efficient
     * - May allow bursts at window boundaries
     * - Good for most use cases
     */
    class FixedWindow implements RateLimiter {

        private final String name;
        private final long maxRequests;
        private final Duration windowSize;
        private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

        FixedWindow(String name, long maxRequests, Duration windowSize) {
            this.name = name;
            this.maxRequests = maxRequests;
            this.windowSize = windowSize;
        }

        @Override
        public boolean tryAcquire(String key) {
            return tryAcquire(key, 1);
        }

        @Override
        public boolean tryAcquire(String key, int permits) {
            WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter());
            return counter.tryAcquire(permits, maxRequests, windowSize);
        }

        @Override
        public boolean acquire(String key, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            
            while (System.nanoTime() < deadline) {
                if (tryAcquire(key)) {
                    return true;
                }
                Thread.sleep(Math.min(10, timeout.toMillis() / 10));
            }
            return false;
        }

        @Override
        public long getRemainingPermits(String key) {
            WindowCounter counter = counters.get(key);
            if (counter == null) return maxRequests;
            return counter.getRemainingPermits(maxRequests, windowSize);
        }

        @Override
        public Optional<Duration> getTimeUntilNextPermit(String key) {
            WindowCounter counter = counters.get(key);
            if (counter == null) return Optional.empty();
            return counter.getTimeUntilNextWindow(windowSize);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Algorithm getAlgorithm() {
            return Algorithm.FIXED_WINDOW;
        }

        @Override
        public void reset(String key) {
            counters.remove(key);
        }

        @Override
        public void resetAll() {
            counters.clear();
        }

        private static class WindowCounter {
            private final AtomicLong count = new AtomicLong(0);
            private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

            boolean tryAcquire(int permits, long maxRequests, Duration windowSize) {
                maybeResetWindow(windowSize);
                
                while (true) {
                    long current = count.get();
                    if (current + permits > maxRequests) {
                        return false;
                    }
                    if (count.compareAndSet(current, current + permits)) {
                        return true;
                    }
                }
            }

            long getRemainingPermits(long maxRequests, Duration windowSize) {
                maybeResetWindow(windowSize);
                return Math.max(0, maxRequests - count.get());
            }

            Optional<Duration> getTimeUntilNextWindow(Duration windowSize) {
                long now = System.currentTimeMillis();
                long start = windowStart.get();
                long elapsed = now - start;
                
                if (elapsed >= windowSize.toMillis()) {
                    return Optional.empty();
                }
                return Optional.of(Duration.ofMillis(windowSize.toMillis() - elapsed));
            }

            private void maybeResetWindow(Duration windowSize) {
                long now = System.currentTimeMillis();
                long start = windowStart.get();
                
                if (now - start >= windowSize.toMillis()) {
                    if (windowStart.compareAndSet(start, now)) {
                        count.set(0);
                    }
                }
            }
        }
    }

    // ==================== BUILDERS ====================

    class TokenBucketBuilder {
        private String name = "default";
        private long capacity = 100;
        private long refillTokens = 10;
        private Duration refillPeriod = Duration.ofSeconds(1);

        public TokenBucketBuilder name(String name) {
            this.name = name;
            return this;
        }

        public TokenBucketBuilder capacity(long capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
            this.capacity = capacity;
            return this;
        }

        public TokenBucketBuilder refillRate(long tokensPerPeriod) {
            if (tokensPerPeriod <= 0) throw new IllegalArgumentException("Refill rate must be positive");
            this.refillTokens = tokensPerPeriod;
            return this;
        }

        public TokenBucketBuilder refillPeriod(Duration period) {
            if (period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("Refill period must be positive");
            }
            this.refillPeriod = period;
            return this;
        }

        public RateLimiter build() {
            return new TokenBucket(name, capacity, refillTokens, refillPeriod);
        }
    }

    class SlidingWindowBuilder {
        private String name = "default";
        private long maxRequests = 100;
        private Duration windowSize = Duration.ofMinutes(1);

        public SlidingWindowBuilder name(String name) {
            this.name = name;
            return this;
        }

        public SlidingWindowBuilder maxRequests(long maxRequests) {
            if (maxRequests <= 0) throw new IllegalArgumentException("Max requests must be positive");
            this.maxRequests = maxRequests;
            return this;
        }

        public SlidingWindowBuilder windowSize(Duration windowSize) {
            if (windowSize.isZero() || windowSize.isNegative()) {
                throw new IllegalArgumentException("Window size must be positive");
            }
            this.windowSize = windowSize;
            return this;
        }

        public RateLimiter build() {
            return new SlidingWindow(name, maxRequests, windowSize);
        }
    }

    class FixedWindowBuilder {
        private String name = "default";
        private long maxRequests = 100;
        private Duration windowSize = Duration.ofMinutes(1);

        public FixedWindowBuilder name(String name) {
            this.name = name;
            return this;
        }

        public FixedWindowBuilder maxRequests(long maxRequests) {
            if (maxRequests <= 0) throw new IllegalArgumentException("Max requests must be positive");
            this.maxRequests = maxRequests;
            return this;
        }

        public FixedWindowBuilder windowSize(Duration windowSize) {
            if (windowSize.isZero() || windowSize.isNegative()) {
                throw new IllegalArgumentException("Window size must be positive");
            }
            this.windowSize = windowSize;
            return this;
        }

        public RateLimiter build() {
            return new FixedWindow(name, maxRequests, windowSize);
        }
    }
}
