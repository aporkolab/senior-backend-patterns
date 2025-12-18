package com.aporkolab.patterns.benchmark;

import com.aporkolab.patterns.ratelimiter.RateLimiter;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JMH Benchmarks for Senior Backend Patterns.
 * 
 * Run with:
 * mvn clean install -DskipTests
 * java -jar benchmarks/target/benchmarks.jar
 * 
 * Or with specific benchmark:
 * java -jar benchmarks/target/benchmarks.jar CircuitBreakerBenchmark
 */
public class PatternsBenchmark {

    // ==================== CIRCUIT BREAKER BENCHMARKS ====================

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 3, time = 1)
    @Measurement(iterations = 5, time = 1)
    @Fork(1)
    public static class CircuitBreakerBenchmark {

        private CircuitBreaker circuitBreaker;
        private AtomicInteger counter;

        @Setup
        public void setup() {
            circuitBreaker = CircuitBreaker.builder()
                    .name("benchmark")
                    .failureThreshold(100)
                    .successThreshold(3)
                    .openDurationMs(60000)
                    .build();
            counter = new AtomicInteger(0);
        }

        @Benchmark
        public void successfulExecution(Blackhole bh) throws Exception {
            bh.consume(circuitBreaker.execute(() -> counter.incrementAndGet()));
        }

        @Benchmark
        public void executionWithFallback(Blackhole bh) {
            bh.consume(circuitBreaker.executeWithFallback(
                    () -> counter.incrementAndGet(),
                    () -> -1));
        }

        @Benchmark
        public void stateCheck(Blackhole bh) {
            bh.consume(circuitBreaker.getState());
        }

        @Benchmark
        @Threads(8)
        public void concurrentExecution(Blackhole bh) throws Exception {
            bh.consume(circuitBreaker.execute(() -> counter.incrementAndGet()));
        }
    }

    // ==================== RATE LIMITER BENCHMARKS ====================

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 3, time = 1)
    @Measurement(iterations = 5, time = 1)
    @Fork(1)
    public static class RateLimiterBenchmark {

        private RateLimiter tokenBucket;
        private RateLimiter slidingWindow;
        private RateLimiter fixedWindow;
        private AtomicInteger keyCounter;

        @Setup
        public void setup() {
            tokenBucket = RateLimiter.tokenBucket()
                    .name("benchmark-token")
                    .capacity(1_000_000)
                    .refillRate(1_000_000)
                    .refillPeriod(Duration.ofSeconds(1))
                    .build();

            slidingWindow = RateLimiter.slidingWindow()
                    .name("benchmark-sliding")
                    .maxRequests(1_000_000)
                    .windowSize(Duration.ofSeconds(1))
                    .build();

            fixedWindow = RateLimiter.fixedWindow()
                    .name("benchmark-fixed")
                    .maxRequests(1_000_000)
                    .windowSize(Duration.ofSeconds(1))
                    .build();

            keyCounter = new AtomicInteger(0);
        }

        @Benchmark
        public void tokenBucketSingleKey(Blackhole bh) {
            bh.consume(tokenBucket.tryAcquire("single-key"));
        }

        @Benchmark
        public void tokenBucketMultipleKeys(Blackhole bh) {
            String key = "key-" + (keyCounter.incrementAndGet() % 1000);
            bh.consume(tokenBucket.tryAcquire(key));
        }

        @Benchmark
        public void slidingWindowSingleKey(Blackhole bh) {
            bh.consume(slidingWindow.tryAcquire("single-key"));
        }

        @Benchmark
        public void fixedWindowSingleKey(Blackhole bh) {
            bh.consume(fixedWindow.tryAcquire("single-key"));
        }

        @Benchmark
        @Threads(8)
        public void tokenBucketConcurrent(Blackhole bh) {
            bh.consume(tokenBucket.tryAcquire("concurrent-key"));
        }

        @Benchmark
        @Threads(8)
        public void fixedWindowConcurrent(Blackhole bh) {
            bh.consume(fixedWindow.tryAcquire("concurrent-key"));
        }

        @Benchmark
        public void getRemainingPermits(Blackhole bh) {
            bh.consume(tokenBucket.getRemainingPermits("single-key"));
        }
    }

    // ==================== COMBINED BENCHMARK ====================

    @BenchmarkMode({Mode.Throughput, Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 3, time = 1)
    @Measurement(iterations = 5, time = 1)
    @Fork(1)
    public static class CombinedPatternsBenchmark {

        private CircuitBreaker circuitBreaker;
        private RateLimiter rateLimiter;

        @Setup
        public void setup() {
            circuitBreaker = CircuitBreaker.builder()
                    .name("combined-cb")
                    .failureThreshold(100)
                    .build();

            rateLimiter = RateLimiter.tokenBucket()
                    .name("combined-rl")
                    .capacity(1_000_000)
                    .refillRate(1_000_000)
                    .refillPeriod(Duration.ofSeconds(1))
                    .build();
        }

        @Benchmark
        public void rateLimitThenCircuitBreaker(Blackhole bh) throws Exception {
            if (rateLimiter.tryAcquire("user")) {
                bh.consume(circuitBreaker.execute(() -> "success"));
            }
        }

        @Benchmark
        @Threads(4)
        public void concurrentCombinedFlow(Blackhole bh) throws Exception {
            String key = Thread.currentThread().getName();
            if (rateLimiter.tryAcquire(key)) {
                bh.consume(circuitBreaker.execute(() -> "success"));
            }
        }
    }

    // ==================== MAIN METHOD ====================

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(PatternsBenchmark.class.getSimpleName())
                .forks(1)
                .build();

        new Runner(opt).run();
    }
}
