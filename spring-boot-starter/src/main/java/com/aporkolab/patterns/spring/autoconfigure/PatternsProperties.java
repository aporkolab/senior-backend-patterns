package com.aporkolab.patterns.spring.autoconfigure;

import com.aporkolab.patterns.ratelimiter.RateLimiter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Senior Backend Patterns.
 * 
 * Example application.yml:
 * <pre>
 * patterns:
 *   enabled: true
 *   circuit-breaker:
 *     name: my-service
 *     failure-threshold: 5
 *     success-threshold: 3
 *     open-duration-ms: 30000
 *   rate-limiter:
 *     algorithm: TOKEN_BUCKET
 *     capacity: 100
 *     refill-rate: 10
 *     refill-period-ms: 1000
 *   metrics:
 *     enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "patterns")
public class PatternsProperties {

    private boolean enabled = true;
    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();
    private RateLimiterProperties rateLimiter = new RateLimiterProperties();
    private OutboxProperties outbox = new OutboxProperties();
    private DlqProperties dlq = new DlqProperties();
    private HttpClientProperties httpClient = new HttpClientProperties();
    private MetricsProperties metrics = new MetricsProperties();

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public CircuitBreakerProperties getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerProperties circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public RateLimiterProperties getRateLimiter() {
        return rateLimiter;
    }

    public void setRateLimiter(RateLimiterProperties rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public OutboxProperties getOutbox() {
        return outbox;
    }

    public void setOutbox(OutboxProperties outbox) {
        this.outbox = outbox;
    }

    public DlqProperties getDlq() {
        return dlq;
    }

    public void setDlq(DlqProperties dlq) {
        this.dlq = dlq;
    }

    public HttpClientProperties getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClientProperties httpClient) {
        this.httpClient = httpClient;
    }

    public MetricsProperties getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsProperties metrics) {
        this.metrics = metrics;
    }

    // ==================== NESTED PROPERTIES CLASSES ====================

    public static class CircuitBreakerProperties {
        private boolean enabled = true;
        private String name = "default";
        private int failureThreshold = 5;
        private int successThreshold = 3;
        private long openDurationMs = 30000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public int getSuccessThreshold() {
            return successThreshold;
        }

        public void setSuccessThreshold(int successThreshold) {
            this.successThreshold = successThreshold;
        }

        public long getOpenDurationMs() {
            return openDurationMs;
        }

        public void setOpenDurationMs(long openDurationMs) {
            this.openDurationMs = openDurationMs;
        }
    }

    public static class RateLimiterProperties {
        private boolean enabled = true;
        private String name = "default";
        private RateLimiter.Algorithm algorithm = RateLimiter.Algorithm.TOKEN_BUCKET;
        
        // Token Bucket specific
        private long capacity = 100;
        private long refillRate = 10;
        private long refillPeriodMs = 1000;
        
        // Window-based specific
        private long maxRequests = 100;
        private long windowSizeMs = 60000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public RateLimiter.Algorithm getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(RateLimiter.Algorithm algorithm) {
            this.algorithm = algorithm;
        }

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public long getRefillRate() {
            return refillRate;
        }

        public void setRefillRate(long refillRate) {
            this.refillRate = refillRate;
        }

        public long getRefillPeriodMs() {
            return refillPeriodMs;
        }

        public void setRefillPeriodMs(long refillPeriodMs) {
            this.refillPeriodMs = refillPeriodMs;
        }

        public long getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(long maxRequests) {
            this.maxRequests = maxRequests;
        }

        public long getWindowSizeMs() {
            return windowSizeMs;
        }

        public void setWindowSizeMs(long windowSizeMs) {
            this.windowSizeMs = windowSizeMs;
        }
    }

    public static class OutboxProperties {
        private boolean enabled = true;
        private int batchSize = 100;
        private long pollIntervalMs = 1000;
        private long cleanupAfterDays = 7;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getCleanupAfterDays() {
            return cleanupAfterDays;
        }

        public void setCleanupAfterDays(long cleanupAfterDays) {
            this.cleanupAfterDays = cleanupAfterDays;
        }
    }

    public static class DlqProperties {
        private boolean enabled = true;
        private String name = "default";
        private int maxRetries = 3;
        private String dlqTopicSuffix = ".dlq";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public String getDlqTopicSuffix() {
            return dlqTopicSuffix;
        }

        public void setDlqTopicSuffix(String dlqTopicSuffix) {
            this.dlqTopicSuffix = dlqTopicSuffix;
        }
    }

    public static class HttpClientProperties {
        private boolean enabled = true;
        private String name = "default";
        private int maxRetries = 3;
        private long initialBackoffMs = 100;
        private long maxBackoffMs = 5000;
        private double jitterFactor = 0.2;
        private long connectTimeoutMs = 5000;
        private long readTimeoutMs = 30000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }

        public double getJitterFactor() {
            return jitterFactor;
        }

        public void setJitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public static class MetricsProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
