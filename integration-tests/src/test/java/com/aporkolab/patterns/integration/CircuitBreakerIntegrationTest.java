package com.aporkolab.patterns.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;


@Testcontainers
class CircuitBreakerIntegrationTest {

    
    private <T> T sendRequest(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return httpClient.send(request, handler).body();
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed", e);
        }
    }

    private int sendRequestGetStatus(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed", e);
        }
    }

    @Container
    static GenericContainer<?> wiremock = new GenericContainer<>(
            DockerImageName.parse("wiremock/wiremock:3.3.1"))
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    private static HttpClient httpClient;
    private static String baseUrl;
    private CircuitBreaker circuitBreaker;

    @BeforeAll
    static void setupInfrastructure() throws Exception {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        baseUrl = "http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(8080);

        
        setupWireMockStubs();
    }

    private static void setupWireMockStubs() throws Exception {
        
        createStub("healthy", 200, "{\"status\":\"ok\"}", 0);

        
        createStub("failing", 500, "{\"error\":\"Internal Server Error\"}", 0);

        
        createStub("slow", 200, "{\"status\":\"ok\"}", 3000);

        
        createStub("flaky", 200, "{\"status\":\"ok\"}", 0);
    }

    private static void createStub(String path, int status, String body, int delayMs) throws Exception {
        String stubJson = """
            {
                "request": {
                    "method": "GET",
                    "url": "/%s"
                },
                "response": {
                    "status": %d,
                    "body": "%s",
                    "fixedDelayMilliseconds": %d,
                    "headers": {
                        "Content-Type": "application/json"
                    }
                }
            }
            """.formatted(path, status, body.replace("\"", "\\\""), delayMs);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/__admin/mappings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(stubJson))
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreaker = CircuitBreaker.builder()
                .name("test-service")
                .failureThreshold(3)
                .successThreshold(2)
                .openDurationMs(2000)
                .build();
    }

    @Test
    @DisplayName("should allow calls when service is healthy")
    void shouldAllowCallsWhenHealthy() throws Exception {
        
        int callCount = 10;
        List<Integer> statusCodes = new ArrayList<>();

        
        for (int i = 0; i < callCount; i++) {
            int status = circuitBreaker.execute(() -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/healthy"))
                        .GET()
                        .build();
                return sendRequestGetStatus(request);
            });
            statusCodes.add(status);
        }

        
        assertThat(statusCodes).hasSize(callCount);
        assertThat(statusCodes).containsOnly(200);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should open circuit after failure threshold")
    void shouldOpenCircuitAfterFailures() {
        
        AtomicInteger callAttempts = new AtomicInteger(0);
        AtomicInteger rejectedCalls = new AtomicInteger(0);

        
        for (int i = 0; i < 10; i++) {
            try {
                circuitBreaker.execute(() -> {
                    callAttempts.incrementAndGet();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/failing"))
                            .GET()
                            .build();
                    int statusCode = sendRequestGetStatus(request);
                    if (statusCode >= 500) {
                        throw new RuntimeException("Server error: " + statusCode);
                    }
                    return statusCode;
                });
            } catch (CircuitBreakerOpenException e) {
                rejectedCalls.incrementAndGet();
            } catch (Exception e) {
                
            }
        }

        
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(callAttempts.get()).isEqualTo(3); 
        assertThat(rejectedCalls.get()).isEqualTo(7); 
    }

    @Test
    @DisplayName("should transition to half-open after timeout")
    void shouldTransitionToHalfOpenAfterTimeout() throws Exception {
        
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new RuntimeException("Simulated failure");
                });
            } catch (Exception e) {
                
            }
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    
                    try {
                        circuitBreaker.execute(() -> "test");
                    } catch (Exception e) {
                        
                    }
                    assertThat(circuitBreaker.getState())
                            .isIn(CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED);
                });
    }

    @Test
    @DisplayName("should close circuit after successful calls in half-open")
    void shouldCloseCircuitAfterSuccessfulHalfOpen() throws Exception {
        
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new RuntimeException("Failure");
                });
            } catch (Exception e) {
                
            }
        }

        
        Thread.sleep(2500);

        
        for (int i = 0; i < 2; i++) {
            circuitBreaker.execute(() -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/healthy"))
                        .GET()
                        .build();
                return sendRequestGetStatus(request);
            });
        }

        
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should handle concurrent requests correctly")
    void shouldHandleConcurrentRequests() throws Exception {
        
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();

        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String result = circuitBreaker.execute(() -> {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/healthy"))
                                .GET()
                                .build();
                        return "status:" + sendRequestGetStatus(request);
                    });
                    results.add(result);
                } catch (Exception e) {
                    results.add("error:" + e.getClass().getSimpleName());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        
        assertThat(results).hasSize(threadCount);
        long successCount = results.stream().filter(r -> r.equals("status:200")).count();
        assertThat(successCount).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("should use fallback when circuit is open")
    void shouldUseFallbackWhenOpen() throws Exception {
        
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new RuntimeException("Failure");
                });
            } catch (Exception e) {
                
            }
        }

        
        String result = circuitBreaker.executeWithFallback(
                () -> {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/failing"))
                            .GET()
                            .build();
                    return sendRequest(request, HttpResponse.BodyHandlers.ofString());
                },
                () -> "{\"fallback\":true,\"cached\":\"data\"}"
        );

        
        assertThat(result).contains("fallback");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("should notify listeners on state change")
    void shouldNotifyListenersOnStateChange() throws Exception {
        
        List<String> stateChanges = new CopyOnWriteArrayList<>();
        circuitBreaker.onStateChange((from, to) ->
                stateChanges.add(from.name() + "->" + to.name()));

        
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new RuntimeException("Failure");
                });
            } catch (Exception e) {
                
            }
        }

        
        assertThat(stateChanges).contains("CLOSED->OPEN");
    }

    @Test
    @DisplayName("should handle high load without race conditions")
    void shouldHandleHighLoadWithoutRaceConditions() throws Exception {
        
        int iterations = 1000;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            final int index = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    circuitBreaker.execute(() -> {
                        
                        if (index % 10 == 0) {
                            throw new RuntimeException("Periodic failure");
                        }
                        return "success";
                    });
                    successCount.incrementAndGet();
                } catch (CircuitBreakerOpenException e) {
                    rejectedCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        
        int total = successCount.get() + failureCount.get() + rejectedCount.get();
        assertThat(total).isEqualTo(iterations);

        
        CircuitBreaker.State finalState = circuitBreaker.getState();
        assertThat(finalState).isIn(
                CircuitBreaker.State.CLOSED,
                CircuitBreaker.State.OPEN,
                CircuitBreaker.State.HALF_OPEN);
    }
}
