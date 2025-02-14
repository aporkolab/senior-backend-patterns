package com.aporkolab.patterns.ratelimiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    

    @Nested
    @DisplayName("Token Bucket Algorithm")
    class TokenBucketTests {

        @Test
        @DisplayName("should allow requests within capacity")
        void shouldAllowRequestsWithinCapacity() {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .name("test")
                    .capacity(10)
                    .refillRate(1)
                    .refillPeriod(Duration.ofSeconds(1))
                    .build();

            
            for (int i = 0; i < 10; i++) {
                assertThat(limiter.tryAcquire("user-1")).isTrue();
            }
        }

        @Test
        @DisplayName("should reject requests when bucket is empty")
        void shouldRejectWhenEmpty() {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .capacity(5)
                    .refillRate(1)
                    .refillPeriod(Duration.ofSeconds(10))
                    .build();

            
            for (int i = 0; i < 5; i++) {
                limiter.tryAcquire("user-1");
            }

            
            assertThat(limiter.tryAcquire("user-1")).isFalse();
        }

        @Test
        @DisplayName("should refill tokens over time")
        void shouldRefillOverTime() throws InterruptedException {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .capacity(5)
                    .refillRate(5)
                    .refillPeriod(Duration.ofMillis(100))
                    .build();

            
            for (int i = 0; i < 5; i++) {
                limiter.tryAcquire("user-1");
            }
            assertThat(limiter.tryAcquire("user-1")).isFalse();

            
            Thread.sleep(150);

            
            assertThat(limiter.tryAcquire("user-1")).isTrue();
        }

        @Test
        @DisplayName("should not exceed capacity on refill")
        void shouldNotExceedCapacityOnRefill() throws InterruptedException {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .capacity(10)
                    .refillRate(100) 
                    .refillPeriod(Duration.ofMillis(50))
                    .build();

            
            limiter.tryAcquire("user-1", 3);

            
            Thread.sleep(200);

            
            assertThat(limiter.getRemainingPermits("user-1")).isLessThanOrEqualTo(10);
        }

        @Test
        @DisplayName("should support multiple permits per request")
        void shouldSupportMultiplePermits() {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .capacity(10)
                    .refillRate(1)
                    .refillPeriod(Duration.ofSeconds(1))
                    .build();

            
            assertThat(limiter.tryAcquire("user-1", 5)).isTrue();
            assertThat(limiter.getRemainingPermits("user-1")).isEqualTo(5);

            
            assertThat(limiter.tryAcquire("user-1", 6)).isFalse();
        }

        @Test
        @DisplayName("should isolate rate limits per key")
        void shouldIsolatePerKey() {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .capacity(5)
                    .refillRate(1)
                    .refillPeriod(Duration.ofSeconds(10))
                    .build();

            
            for (int i = 0; i < 5; i++) {
                limiter.tryAcquire("user-1");
            }

            
            for (int i = 0; i < 5; i++) {
                assertThat(limiter.tryAcquire("user-2")).isTrue();
            }
        }
    }

    

    @Nested
    @DisplayName("Sliding Window Algorithm")
    class SlidingWindowTests {

        @Test
        @DisplayName("should allow requests within limit")
        void shouldAllowRequestsWithinLimit() {
            RateLimiter limiter = RateLimiter.slidingWindow()
                    .name("test")
                    .maxRequests(10)
                    .windowSize(Duration.ofMinutes(1))
                    .build();

            for (int i = 0; i < 10; i++) {
                assertThat(limiter.tryAcquire("user-1")).isTrue();
            }
        }

        @Test
        @DisplayName("should reject when window limit reached")
        void shouldRejectWhenLimitReached() {
            RateLimiter limiter = RateLimiter.slidingWindow()
                    .maxRequests(5)
                    .windowSize(Duration.ofMinutes(1))
                    .build();

            
            for (int i = 0; i < 5; i++) {
                limiter.tryAcquire("user-1");
            }

            
            assertThat(limiter.tryAcquire("user-1")).isFalse();
        }

        @Test
        @DisplayName("should slide window over time")
        void shouldSlideWindowOverTime() throws InterruptedException {
            RateLimiter limiter = RateLimiter.slidingWindow()
                    .maxRequests(5)
                    .windowSize(Duration.ofMillis(200))
                    .build();

            
            for (int i = 0; i < 5; i++) {
                limiter.tryAcquire("user-1");
            }
            assertThat(limiter.tryAcquire("user-1")).isFalse();

            
            Thread.sleep(250);

            
            assertThat(limiter.tryAcquire("user-1")).isTrue();
        }

        @Test
        @DisplayName("should return correct remaining permits")
        void shouldReturnCorrectRemainingPermits() {
            RateLimiter limiter = RateLimiter.slidingWindow()
                    .maxRequests(10)
                    .windowSize(Duration.ofMinutes(1))
                    .build();

            assertThat(limiter.getRemainingPermits("user-1")).isEqualTo(10);

            limiter.tryAcquire("user-1", 3);

            assertThat(limiter.getRemainingPermits("user-1")).isEqualTo(7);
        }
    }

    

    @Nested
    @DisplayName("Fixed Window Algorithm")
    class FixedWindowTests {

        @Test
        @DisplayName("should allow requests within window limit")
        void shouldAllowRequestsWithinLimit() {
            RateLimiter limiter = RateLimiter.fixedWindow()
                    .name("test")
                    .maxRequests(10)
                    .windowSize(Duration.ofMinutes(1))
                    .build();

            for (int i = 0; i < 10; i++) {
                assertThat(limiter.tryAcquire("user-1")).isTrue();
            }
        }

        @Test
        @DisplayName("should reject when window limit reached")
        void shouldRejectWhenLimitReached() {
            RateLimiter limiter = RateLimiter.fixedWindow()
                    .maxRequests(5)
                    .windowSize(Duration.ofMinutes(1))
                    .build();

            for (int i = 0; i < 5; i++) {
                limiter.tryAcquire("user-1");
            }

            assertThat(limiter.tryAcquire("user-1")).isFalse();
        }

        @Test
        @DisplayName("should reset counter on new window")
        void shouldResetOnNewWindow() throws InterruptedException {
            RateLimiter limiter = RateLimiter.fixedWindow()
                    .maxRequests(5)
                    .windowSize(Duration.ofMillis(100))
                    .build();

            
            for (int i = 0; i < 5; i++) {
                limiter.tryAcquire("user-1");
            }
            assertThat(limiter.tryAcquire("user-1")).isFalse();

            
            Thread.sleep(150);

            
            assertThat(limiter.tryAcquire("user-1")).isTrue();
            assertThat(limiter.getRemainingPermits("user-1")).isEqualTo(4);
        }

        @Test
        @DisplayName("should return time until next window")
        void shouldReturnTimeUntilNextWindow() throws InterruptedException {
            RateLimiter limiter = RateLimiter.fixedWindow()
                    .maxRequests(1)
                    .windowSize(Duration.ofMillis(500))
                    .build();

            limiter.tryAcquire("user-1");

            Optional<Duration> timeUntilNext = limiter.getTimeUntilNextPermit("user-1");
            assertThat(timeUntilNext).isPresent();
            assertThat(timeUntilNext.get().toMillis()).isLessThanOrEqualTo(500);
        }
    }

    

    @Nested
    @DisplayName("Common Functionality")
    class CommonTests {

        @Test
        @DisplayName("should reset rate limit for specific key")
        void shouldResetKey() {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .capacity(5)
                    .refillRate(1)
                    .refillPeriod(Duration.ofMinutes(1))
                    .build();

            
            for (int i = 0; i < 5; i++) {
                limiter.tryAcquire("user-1");
            }
            assertThat(limiter.tryAcquire("user-1")).isFalse();

            
            limiter.reset("user-1");

            
            assertThat(limiter.getRemainingPermits("user-1")).isEqualTo(5);
        }

        @Test
        @DisplayName("should reset all rate limits")
        void shouldResetAll() {
            RateLimiter limiter = RateLimiter.fixedWindow()
                    .maxRequests(1)
                    .windowSize(Duration.ofMinutes(1))
                    .build();

            limiter.tryAcquire("user-1");
            limiter.tryAcquire("user-2");

            limiter.resetAll();

            assertThat(limiter.getRemainingPermits("user-1")).isEqualTo(1);
            assertThat(limiter.getRemainingPermits("user-2")).isEqualTo(1);
        }

        @Test
        @DisplayName("should execute if allowed")
        void shouldExecuteIfAllowed() {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .capacity(1)
                    .refillRate(1)
                    .refillPeriod(Duration.ofMinutes(1))
                    .build();

            Optional<String> result = limiter.executeIfAllowed("user-1", () -> "success");
            assertThat(result).contains("success");

            Optional<String> blockedResult = limiter.executeIfAllowed("user-1", () -> "blocked");
            assertThat(blockedResult).isEmpty();
        }

        @Test
        @DisplayName("should acquire with blocking and timeout")
        void shouldAcquireWithTimeout() throws InterruptedException {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .capacity(1)
                    .refillRate(1)
                    .refillPeriod(Duration.ofMillis(100))
                    .build();

            
            assertThat(limiter.acquire("user-1", Duration.ofMillis(50))).isTrue();

            
            long start = System.currentTimeMillis();
            assertThat(limiter.acquire("user-1", Duration.ofMillis(200))).isTrue();
            long elapsed = System.currentTimeMillis() - start;

            assertThat(elapsed).isGreaterThanOrEqualTo(50);
        }

        @Test
        @DisplayName("should return correct algorithm type")
        void shouldReturnCorrectAlgorithm() {
            RateLimiter tokenBucket = RateLimiter.tokenBucket().build();
            RateLimiter slidingWindow = RateLimiter.slidingWindow().build();
            RateLimiter fixedWindow = RateLimiter.fixedWindow().build();

            assertThat(tokenBucket.getAlgorithm()).isEqualTo(RateLimiter.Algorithm.TOKEN_BUCKET);
            assertThat(slidingWindow.getAlgorithm()).isEqualTo(RateLimiter.Algorithm.SLIDING_WINDOW);
            assertThat(fixedWindow.getAlgorithm()).isEqualTo(RateLimiter.Algorithm.FIXED_WINDOW);
        }
    }

    

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidationTests {

        @Test
        @DisplayName("should reject zero capacity")
        void shouldRejectZeroCapacity() {
            assertThatThrownBy(() -> RateLimiter.tokenBucket().capacity(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject negative refill rate")
        void shouldRejectNegativeRefillRate() {
            assertThatThrownBy(() -> RateLimiter.tokenBucket().refillRate(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject zero window size")
        void shouldRejectZeroWindowSize() {
            assertThatThrownBy(() -> RateLimiter.slidingWindow().windowSize(Duration.ZERO).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject negative max requests")
        void shouldRejectNegativeMaxRequests() {
            assertThatThrownBy(() -> RateLimiter.fixedWindow().maxRequests(-5).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("should handle concurrent requests safely")
        void shouldHandleConcurrentRequests() throws InterruptedException {
            
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .capacity(100)
                    .refillRate(1)
                    .refillPeriod(Duration.ofHours(1))
                    .build();

            int threadCount = 50;
            int requestsPerThread = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < requestsPerThread; j++) {
                            if (limiter.tryAcquire("shared-key")) {
                                successCount.incrementAndGet();
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
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            
            assertThat(successCount.get()).isEqualTo(100);
        }

        @Test
        @DisplayName("should handle concurrent requests for different keys")
        void shouldHandleConcurrentDifferentKeys() throws InterruptedException {
            RateLimiter limiter = RateLimiter.fixedWindow()
                    .maxRequests(10)
                    .windowSize(Duration.ofMinutes(1))
                    .build();

            int userCount = 10;
            int requestsPerUser = 15;
            ExecutorService executor = Executors.newFixedThreadPool(userCount);
            CountDownLatch doneLatch = new CountDownLatch(userCount);
            List<AtomicInteger> successCounts = new ArrayList<>();

            for (int u = 0; u < userCount; u++) {
                AtomicInteger counter = new AtomicInteger(0);
                successCounts.add(counter);
                final String userId = "user-" + u;

                executor.submit(() -> {
                    try {
                        for (int j = 0; j < requestsPerUser; j++) {
                            if (limiter.tryAcquire(userId)) {
                                counter.incrementAndGet();
                            }
                        }
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            
            for (AtomicInteger count : successCounts) {
                assertThat(count.get()).isEqualTo(10);
            }
        }
    }

    

    @Nested
    @DisplayName("RateLimitExceededException")
    class ExceptionTests {

        @Test
        @DisplayName("should create exception from rate limiter")
        void shouldCreateFromRateLimiter() {
            RateLimiter limiter = RateLimiter.tokenBucket()
                    .name("api-limiter")
                    .capacity(1)
                    .refillRate(1)
                    .refillPeriod(Duration.ofSeconds(10))
                    .build();

            limiter.tryAcquire("user-123");

            RateLimitExceededException ex = RateLimitExceededException.from(limiter, "user-123");

            assertThat(ex.getRateLimiterName()).isEqualTo("api-limiter");
            assertThat(ex.getKey()).isEqualTo("user-123");
            assertThat(ex.getRemainingPermits()).isZero();
        }

        @Test
        @DisplayName("should provide retry-after seconds")
        void shouldProvideRetryAfterSeconds() {
            RateLimitExceededException ex = new RateLimitExceededException(
                    "test", "key", Duration.ofSeconds(5), 0);

            assertThat(ex.getRetryAfterSeconds()).isEqualTo(5);
        }
    }
}
