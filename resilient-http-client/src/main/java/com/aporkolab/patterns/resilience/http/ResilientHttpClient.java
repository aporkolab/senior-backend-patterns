package com.aporkolab.patterns.resilience.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ResilientHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientHttpClient.class);
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(500, 502, 503, 504);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final int maxRetries;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double jitterFactor;
    private final Random random;

    private ResilientHttpClient(Builder builder) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(builder.connectionTimeoutMs))
                .build();
        this.baseUrl = builder.baseUrl;
        this.maxRetries = builder.maxRetries;
        this.initialBackoffMs = builder.initialBackoffMs;
        this.maxBackoffMs = builder.maxBackoffMs;
        this.jitterFactor = builder.jitterFactor;
        this.random = new Random();
    }

    public static Builder builder() {
        return new Builder();
    }

    
    public HttpResponse<String> get(String path) throws HttpClientException {
        return get(path, Map.of());
    }

    public HttpResponse<String> get(String path, Map<String, String> headers) throws HttpClientException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET();
        headers.forEach(requestBuilder::header);
        return executeWithRetry(requestBuilder.build());
    }

    
    public HttpResponse<String> post(String path, String body) throws HttpClientException {
        return post(path, body, Map.of("Content-Type", "application/json"));
    }

    public HttpResponse<String> post(String path, String body, Map<String, String> headers) throws HttpClientException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(requestBuilder::header);
        return executeWithRetry(requestBuilder.build());
    }

    
    public CompletableFuture<HttpResponse<String>> getAsync(String path) {
        return getAsync(path, Map.of());
    }

    public CompletableFuture<HttpResponse<String>> getAsync(String path, Map<String, String> headers) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET();
        headers.forEach(requestBuilder::header);
        return executeWithRetryAsync(requestBuilder.build(), 0);
    }

    private HttpResponse<String> executeWithRetry(HttpRequest request) throws HttpClientException {
        int attempt = 0;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (isRetryableStatusCode(response.statusCode())) {
                    if (attempt < maxRetries) {
                        long backoff = calculateBackoff(attempt);
                        log.warn("Retryable status {} from {} - attempt {}/{}, backing off {}ms",
                                response.statusCode(), request.uri(), attempt + 1, maxRetries, backoff);
                        sleep(backoff);
                        attempt++;
                        continue;
                    } else {
                        
                        throw new HttpClientException(
                                String.format("Request to %s failed after %d attempts with status %d",
                                        request.uri(), attempt + 1, response.statusCode()),
                                null
                        );
                    }
                }
                return response;

            } catch (IOException | InterruptedException e) {
                lastException = e;
                if (attempt < maxRetries) {
                    long backoff = calculateBackoff(attempt);
                    log.warn("Request failed to {} - attempt {}/{}, backing off {}ms: {}",
                            request.uri(), attempt + 1, maxRetries, backoff, e.getMessage());
                    sleep(backoff);
                    attempt++;
                } else {
                    break;
                }
            }
        }

        throw new HttpClientException(
                String.format("Request to %s failed after %d attempts", request.uri(), maxRetries + 1),
                lastException
        );
    }

    private CompletableFuture<HttpResponse<String>> executeWithRetryAsync(HttpRequest request, int attempt) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (isRetryableStatusCode(response.statusCode())) {
                        if (attempt < maxRetries) {
                            long backoff = calculateBackoff(attempt);
                            log.warn("Retryable status {} - attempt {}/{}, backing off {}ms",
                                    response.statusCode(), attempt + 1, maxRetries, backoff);
                            return delay(backoff).thenCompose(v -> executeWithRetryAsync(request, attempt + 1));
                        } else {
                            
                            return CompletableFuture.failedFuture(new HttpClientException(
                                    String.format("Request failed after %d attempts with status %d",
                                            attempt + 1, response.statusCode()),
                                    null
                            ));
                        }
                    }
                    return CompletableFuture.completedFuture(response);
                })
                .exceptionallyCompose(ex -> {
                    
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;

                    
                    if (cause instanceof HttpClientException) {
                        return CompletableFuture.failedFuture(cause);
                    }

                    if (attempt < maxRetries) {
                        long backoff = calculateBackoff(attempt);
                        log.warn("Request failed - attempt {}/{}, backing off {}ms: {}",
                                attempt + 1, maxRetries, backoff, ex.getMessage());
                        return delay(backoff).thenCompose(v -> executeWithRetryAsync(request, attempt + 1));
                    }
                    return CompletableFuture.failedFuture(
                            new HttpClientException("Request failed after " + (maxRetries + 1) + " attempts", ex)
                    );
                });
    }

    private boolean isRetryableStatusCode(int statusCode) {
        return RETRYABLE_STATUS_CODES.contains(statusCode);
    }

    
    private long calculateBackoff(int attempt) {
        long exponentialBackoff = (long) (initialBackoffMs * Math.pow(2, attempt));
        long cappedBackoff = Math.min(exponentialBackoff, maxBackoffMs);
        double jitter = 1 + (random.nextDouble() * jitterFactor);
        return (long) (cappedBackoff * jitter);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CompletableFuture<Void> delay(long ms) {
        return CompletableFuture.runAsync(() -> sleep(ms));
    }

    public static class Builder {
        private String baseUrl = "";
        private int maxRetries = 3;
        private long initialBackoffMs = 100;
        private long maxBackoffMs = 5000;
        private long connectionTimeoutMs = 5000;
        private long readTimeoutMs = 30000;
        private double jitterFactor = 0.2;

        public Builder baseUrl(String baseUrl) {
            
            String trimmed = baseUrl;
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            this.baseUrl = trimmed;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0 || maxRetries > 10) {
                throw new IllegalArgumentException("maxRetries must be between 0 and 10");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder initialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
            return this;
        }

        public Builder maxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
            return this;
        }

        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        public Builder readTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        public Builder jitterFactor(double jitterFactor) {
            if (jitterFactor < 0 || jitterFactor > 1) {
                throw new IllegalArgumentException("jitterFactor must be between 0 and 1");
            }
            this.jitterFactor = jitterFactor;
            return this;
        }

        public ResilientHttpClient build() {
            return new ResilientHttpClient(this);
        }
    }
}
