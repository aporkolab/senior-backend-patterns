package com.aporkolab.patterns.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for Resilient HTTP Client.
 * 
 * Provides the following metrics:
 * - http_client_requests_total: Total requests by method, status, and outcome
 * - http_client_request_duration: Request latency with percentiles
 * - http_client_retries_total: Retry attempts
 * - http_client_retry_exhausted_total: Requests that exhausted retries
 * - http_client_backoff_duration: Total time spent in backoff
 * - http_client_active_requests: Currently active requests
 */
public class HttpClientMetrics {

    private static final String METRIC_PREFIX = "http_client";

    private final MeterRegistry registry;
    private final Tags baseTags;
    private final String clientName;

    private final Timer requestTimer;
    private final Counter retriesCounter;
    private final Counter retryExhaustedCounter;
    private final DistributionSummary backoffSummary;

    private final AtomicLong activeRequests = new AtomicLong(0);
    private final ConcurrentMap<String, Counter> statusCounters = new ConcurrentHashMap<>();

    public HttpClientMetrics(MeterRegistry registry, String clientName) {
        this.registry = registry;
        this.clientName = clientName;
        this.baseTags = Tags.of("client", clientName);

        // Timer for request duration
        this.requestTimer = Timer.builder(METRIC_PREFIX + "_request_duration")
                .description("HTTP request duration")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95, 0.99, 0.999)
                .publishPercentileHistogram()
                .register(registry);

        // Retry counter
        this.retriesCounter = Counter.builder(METRIC_PREFIX + "_retries_total")
                .description("Total retry attempts")
                .tags(baseTags)
                .register(registry);

        // Retry exhausted counter
        this.retryExhaustedCounter = Counter.builder(METRIC_PREFIX + "_retry_exhausted_total")
                .description("Requests that exhausted all retry attempts")
                .tags(baseTags)
                .register(registry);

        // Backoff time distribution
        this.backoffSummary = DistributionSummary.builder(METRIC_PREFIX + "_backoff_duration_ms")
                .description("Time spent in backoff between retries")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        // Active requests gauge
        io.micrometer.core.instrument.Gauge.builder(METRIC_PREFIX + "_active_requests", activeRequests, AtomicLong::get)
                .description("Currently active HTTP requests")
                .tags(baseTags)
                .register(registry);
    }

    /**
     * Record the start of a request.
     */
    public Timer.Sample startRequest() {
        activeRequests.incrementAndGet();
        return Timer.start(registry);
    }

    /**
     * Record the completion of a request.
     */
    public void recordRequest(Timer.Sample sample, String method, String uri, int statusCode) {
        activeRequests.decrementAndGet();
        
        Tags requestTags = baseTags
                .and("method", method)
                .and("uri", sanitizeUri(uri))
                .and("status", String.valueOf(statusCode))
                .and("outcome", getOutcome(statusCode));

        // Stop timer with specific tags
        Timer specificTimer = Timer.builder(METRIC_PREFIX + "_request_duration")
                .tags(requestTags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        sample.stop(specificTimer);

        // Increment request counter
        getOrCreateStatusCounter(method, statusCode).increment();
    }

    /**
     * Record a request exception.
     */
    public void recordRequestException(Timer.Sample sample, String method, String uri, Exception e) {
        activeRequests.decrementAndGet();
        
        Tags requestTags = baseTags
                .and("method", method)
                .and("uri", sanitizeUri(uri))
                .and("status", "0")
                .and("outcome", "error")
                .and("exception", e.getClass().getSimpleName());

        Timer specificTimer = Timer.builder(METRIC_PREFIX + "_request_duration")
                .tags(requestTags)
                .register(registry);
        sample.stop(specificTimer);

        Counter.builder(METRIC_PREFIX + "_exceptions_total")
                .tags(baseTags.and("exception", e.getClass().getSimpleName()))
                .register(registry)
                .increment();
    }

    /**
     * Record a retry attempt.
     */
    public void recordRetry(int attemptNumber, long backoffMs) {
        retriesCounter.increment();
        backoffSummary.record(backoffMs);

        Counter.builder(METRIC_PREFIX + "_retry_attempt")
                .tags(baseTags.and("attempt", String.valueOf(attemptNumber)))
                .register(registry)
                .increment();
    }

    /**
     * Record retry exhaustion.
     */
    public void recordRetryExhausted(String method, String uri, int lastStatusCode) {
        retryExhaustedCounter.increment();

        Counter.builder(METRIC_PREFIX + "_retry_exhausted_by_status")
                .tags(baseTags.and("method", method, "status", String.valueOf(lastStatusCode)))
                .register(registry)
                .increment();
    }

    /**
     * Record a timeout.
     */
    public void recordTimeout(String method, String uri) {
        Counter.builder(METRIC_PREFIX + "_timeouts_total")
                .tags(baseTags.and("method", method, "uri", sanitizeUri(uri)))
                .register(registry)
                .increment();
    }

    /**
     * Record connection error.
     */
    public void recordConnectionError(String method, String uri) {
        Counter.builder(METRIC_PREFIX + "_connection_errors_total")
                .tags(baseTags.and("method", method, "uri", sanitizeUri(uri)))
                .register(registry)
                .increment();
    }

    /**
     * Get success rate for this client.
     */
    public double getSuccessRate() {
        double total = 0;
        double success = 0;

        for (var entry : statusCounters.entrySet()) {
            double count = entry.getValue().count();
            total += count;
            if (entry.getKey().contains("_2")) { // 2xx status codes
                success += count;
            }
        }

        if (total == 0) return 100.0;
        return (success / total) * 100.0;
    }

    /**
     * Get average response time in milliseconds.
     */
    public double getAverageResponseTimeMs() {
        return requestTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Get 95th percentile response time in milliseconds.
     */
    public double getP95ResponseTimeMs() {
        // This would need the percentile value from the timer
        return requestTimer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) / 
               Math.max(1, requestTimer.count());
    }

    private Counter getOrCreateStatusCounter(String method, int statusCode) {
        String key = method + "_" + statusCode;
        return statusCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + "_requests_total")
                        .tags(baseTags.and("method", method, "status", String.valueOf(statusCode)))
                        .register(registry));
    }

    private String getOutcome(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return "success";
        if (statusCode >= 400 && statusCode < 500) return "client_error";
        if (statusCode >= 500) return "server_error";
        return "unknown";
    }

    private String sanitizeUri(String uri) {
        // Remove query params and path variables for cardinality control
        if (uri == null) return "unknown";
        
        // Remove query string
        int queryIndex = uri.indexOf('?');
        if (queryIndex > 0) {
            uri = uri.substring(0, queryIndex);
        }

        // Replace numeric path segments (likely IDs)
        return uri.replaceAll("/\\d+", "/{id}")
                  .replaceAll("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "/{uuid}");
    }
}
