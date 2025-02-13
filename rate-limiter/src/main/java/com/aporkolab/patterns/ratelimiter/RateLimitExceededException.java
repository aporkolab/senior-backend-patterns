package com.aporkolab.patterns.ratelimiter;

import java.time.Duration;
import java.util.Optional;

/**
 * Exception thrown when a request is rate limited.
 * 
 * Contains information about:
 * - The rate limiter that rejected the request
 * - The key that was rate limited
 * - Time until the next permit is available
 * - Remaining permits (if any)
 */
public class RateLimitExceededException extends RuntimeException {

    private final String rateLimiterName;
    private final String key;
    private final Duration retryAfter;
    private final long remainingPermits;

    public RateLimitExceededException(String rateLimiterName, String key) {
        this(rateLimiterName, key, null, 0);
    }

    public RateLimitExceededException(String rateLimiterName, String key, 
                                       Duration retryAfter, long remainingPermits) {
        super(String.format("Rate limit exceeded for key '%s' on limiter '%s'. " +
                "Retry after: %s", key, rateLimiterName, 
                retryAfter != null ? retryAfter.toMillis() + "ms" : "unknown"));
        this.rateLimiterName = rateLimiterName;
        this.key = key;
        this.retryAfter = retryAfter;
        this.remainingPermits = remainingPermits;
    }

    /**
     * Creates exception from a rate limiter instance.
     */
    public static RateLimitExceededException from(RateLimiter limiter, String key) {
        Optional<Duration> retryAfter = limiter.getTimeUntilNextPermit(key);
        long remaining = limiter.getRemainingPermits(key);
        return new RateLimitExceededException(
                limiter.getName(), 
                key, 
                retryAfter.orElse(null), 
                remaining);
    }

    public String getRateLimiterName() {
        return rateLimiterName;
    }

    public String getKey() {
        return key;
    }

    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public long getRemainingPermits() {
        return remainingPermits;
    }

    /**
     * Get the Retry-After header value in seconds.
     */
    public long getRetryAfterSeconds() {
        return retryAfter != null ? Math.max(1, retryAfter.getSeconds()) : 1;
    }
}
