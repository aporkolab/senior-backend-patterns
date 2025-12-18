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
import java.util.regex.Pattern;


public class HttpClientMetrics {

    private static final String METRIC_PREFIX = "http_client";

    
    private static final Pattern NUMERIC_PATH_PATTERN = Pattern.compile("/\\d+");
    private static final Pattern UUID_PATH_PATTERN = Pattern.compile(
            "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE
    );

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

        
        this.requestTimer = Timer.builder(METRIC_PREFIX + "_request_duration")
                .description("HTTP request duration")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95, 0.99, 0.999)
                .publishPercentileHistogram()
                .register(registry);

        
        this.retriesCounter = Counter.builder(METRIC_PREFIX + "_retries_total")
                .description("Total retry attempts")
                .tags(baseTags)
                .register(registry);

        
        this.retryExhaustedCounter = Counter.builder(METRIC_PREFIX + "_retry_exhausted_total")
                .description("Requests that exhausted all retry attempts")
                .tags(baseTags)
                .register(registry);

        
        this.backoffSummary = DistributionSummary.builder(METRIC_PREFIX + "_backoff_duration_ms")
                .description("Time spent in backoff between retries")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        
        io.micrometer.core.instrument.Gauge.builder(METRIC_PREFIX + "_active_requests", activeRequests, AtomicLong::get)
                .description("Currently active HTTP requests")
                .tags(baseTags)
                .register(registry);
    }

    
    public Timer.Sample startRequest() {
        activeRequests.incrementAndGet();
        return Timer.start(registry);
    }

    
    public void recordRequest(Timer.Sample sample, String method, String uri, int statusCode) {
        activeRequests.decrementAndGet();
        
        Tags requestTags = baseTags
                .and("method", method)
                .and("uri", sanitizeUri(uri))
                .and("status", String.valueOf(statusCode))
                .and("outcome", getOutcome(statusCode));

        
        Timer specificTimer = Timer.builder(METRIC_PREFIX + "_request_duration")
                .tags(requestTags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        sample.stop(specificTimer);

        
        getOrCreateStatusCounter(method, statusCode).increment();
    }

    
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

    
    public void recordRetry(int attemptNumber, long backoffMs) {
        retriesCounter.increment();
        backoffSummary.record(backoffMs);

        Counter.builder(METRIC_PREFIX + "_retry_attempt")
                .tags(baseTags.and("attempt", String.valueOf(attemptNumber)))
                .register(registry)
                .increment();
    }

    
    public void recordRetryExhausted(String method, String uri, int lastStatusCode) {
        retryExhaustedCounter.increment();

        Counter.builder(METRIC_PREFIX + "_retry_exhausted_by_status")
                .tags(baseTags.and("method", method, "status", String.valueOf(lastStatusCode)))
                .register(registry)
                .increment();
    }

    
    public void recordTimeout(String method, String uri) {
        Counter.builder(METRIC_PREFIX + "_timeouts_total")
                .tags(baseTags.and("method", method, "uri", sanitizeUri(uri)))
                .register(registry)
                .increment();
    }

    
    public void recordConnectionError(String method, String uri) {
        Counter.builder(METRIC_PREFIX + "_connection_errors_total")
                .tags(baseTags.and("method", method, "uri", sanitizeUri(uri)))
                .register(registry)
                .increment();
    }

    
    public double getSuccessRate() {
        double total = 0;
        double success = 0;

        for (var entry : statusCounters.entrySet()) {
            double count = entry.getValue().count();
            total += count;
            if (entry.getKey().contains("_2")) { 
                success += count;
            }
        }

        if (total == 0) return 100.0;
        return (success / total) * 100.0;
    }

    
    public double getAverageResponseTimeMs() {
        return requestTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    
    public double getP95ResponseTimeMs() {
        
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
        
        if (uri == null) return "unknown";

        
        int queryIndex = uri.indexOf('?');
        if (queryIndex > 0) {
            uri = uri.substring(0, queryIndex);
        }

        
        uri = NUMERIC_PATH_PATTERN.matcher(uri).replaceAll("/{id}");
        uri = UUID_PATH_PATTERN.matcher(uri).replaceAll("/{uuid}");
        return uri;
    }
}
