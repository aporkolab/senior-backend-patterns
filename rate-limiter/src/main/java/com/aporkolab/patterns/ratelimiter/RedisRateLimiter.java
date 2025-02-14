package com.aporkolab.patterns.ratelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis-backed distributed rate limiter using Token Bucket algorithm.
 * 
 * Requires Redis and uses Lua scripts for atomic operations.
 * Suitable for distributed systems where rate limits must be shared across instances.
 * 
 * Usage:
 * <pre>
 * RedisRateLimiter limiter = RedisRateLimiter.builder()
 *     .name("api-gateway")
 *     .capacity(100)
 *     .refillRate(10)
 *     .redisTemplate(redisTemplate)
 *     .build();
 * 
 * if (limiter.tryAcquire("user-123")) {
 *     processRequest();
 * }
 * </pre>
 */
public class RedisRateLimiter {

    private static final String KEY_PREFIX = "rate_limiter:";
    
    // Lua script for atomic token bucket operation
    private static final String TOKEN_BUCKET_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local refill_period_ms = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local now = tonumber(ARGV[5])
            
            local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(bucket[1])
            local last_refill = tonumber(bucket[2])
            
            if tokens == nil then
                tokens = capacity
                last_refill = now
            end
            
            -- Calculate refill
            local elapsed = now - last_refill
            local refill_count = math.floor(elapsed / refill_period_ms) * refill_rate
            tokens = math.min(capacity, tokens + refill_count)
            
            if refill_count > 0 then
                last_refill = now
            end
            
            local allowed = 0
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            end
            
            redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)
            redis.call('PEXPIRE', key, refill_period_ms * (capacity / refill_rate) * 2)
            
            return {allowed, tokens}
            """;

    private final String name;
    private final int capacity;
    private final int refillRate;
    private final long refillPeriodMs;
    private final Object redisTemplate; // Would be RedisTemplate<String, String> in real impl
    private final String scriptSha;

    private RedisRateLimiter(Builder builder) {
        this.name = builder.name;
        this.capacity = builder.capacity;
        this.refillRate = builder.refillRate;
        this.refillPeriodMs = builder.refillPeriodMs;
        this.redisTemplate = builder.redisTemplate;
        this.scriptSha = null; // Would be loaded on startup
    }

    /**
     * Tries to acquire a permit for the given key.
     * 
     * @param key the rate limit key (e.g., user ID, IP address)
     * @return true if permit was acquired, false if rate limited
     */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /**
     * Tries to acquire multiple permits for the given key.
     * 
     * @param key the rate limit key
     * @param permits number of permits to acquire
     * @return true if all permits were acquired, false if rate limited
     */
    public boolean tryAcquire(String key, int permits) {
        Objects.requireNonNull(key, "Key cannot be null");
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be positive");
        }

        String redisKey = KEY_PREFIX + name + ":" + key;
        long now = System.currentTimeMillis();

        // In real implementation:
        // List<Object> result = redisTemplate.execute(
        //     new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, List.class),
        //     List.of(redisKey),
        //     String.valueOf(capacity),
        //     String.valueOf(refillRate),
        //     String.valueOf(refillPeriodMs),
        //     String.valueOf(permits),
        //     String.valueOf(now)
        // );
        // return ((Long) result.get(0)) == 1L;

        // Placeholder for compilation
        throw new UnsupportedOperationException(
                "Redis integration requires spring-data-redis dependency. " +
                "Configure with: spring.data.redis.host and spring.data.redis.port"
        );
    }

    /**
     * Gets the remaining permits for a key without consuming any.
     */
    public int getRemainingPermits(String key) {
        String redisKey = KEY_PREFIX + name + ":" + key;
        
        // In real implementation:
        // String tokens = redisTemplate.opsForHash().get(redisKey, "tokens");
        // return tokens != null ? Integer.parseInt(tokens) : capacity;
        
        throw new UnsupportedOperationException("Redis integration required");
    }

    public String getName() {
        return name;
    }

    public int getCapacity() {
        return capacity;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name = "default";
        private int capacity = 100;
        private int refillRate = 10;
        private long refillPeriodMs = 1000;
        private Object redisTemplate;

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name);
            return this;
        }

        public Builder capacity(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
            this.capacity = capacity;
            return this;
        }

        public Builder refillRate(int refillRate) {
            if (refillRate <= 0) throw new IllegalArgumentException("Refill rate must be positive");
            this.refillRate = refillRate;
            return this;
        }

        public Builder refillPeriod(Duration period) {
            this.refillPeriodMs = period.toMillis();
            return this;
        }

        public Builder redisTemplate(Object redisTemplate) {
            this.redisTemplate = Objects.requireNonNull(redisTemplate);
            return this;
        }

        public RedisRateLimiter build() {
            if (redisTemplate == null) {
                throw new IllegalStateException("RedisTemplate is required");
            }
            return new RedisRateLimiter(this);
        }
    }
}
