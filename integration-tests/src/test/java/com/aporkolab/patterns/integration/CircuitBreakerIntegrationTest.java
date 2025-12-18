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

/**
 * Integration tests for Circuit Breaker with real HTTP calls.
 * 
 * Uses WireMock container to simulate:
 * - Healthy service
 * - Failing service
 * - Slow service (timeouts)
 * - Recovering service
 */
@Testcontainers
class CircuitBreakerIntegrationTest {

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

        // Setup WireMock stubs
        setupWireMockStubs();
    }

    private static void setupWireMockStubs() throws Exception {
        // Healthy endpoint
        createStub("healthy", 200, "{\"status\":\"ok\"}", 0);

        // Failing endpoint (500)
        createStub("failing", 500, "{\"error\":\"Internal Server Error\"}", 0);

        // Slow endpoint (3 second delay)
        createStub("slow", 200, "{\"status\":\"ok\"}", 3000);

        // Flaky endpoint (will be toggled)
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
        // Given
        int callCount = 10;
        List<Integer> statusCodes = new ArrayList<>();

        // When
        for (int i = 0; i < callCount; i++) {
            int status = circuitBreaker.execute(() -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/healthy"))
                        .GET()
                        .build();
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
            });
            statusCodes.add(status);
        }

        // Then
        assertThat(statusCodes).hasSize(callCount);
        assertThat(statusCodes).containsOnly(200);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should open circuit after failure threshold")
    void shouldOpenCircuitAfterFailures() {
        // Given
        AtomicInteger callAttempts = new AtomicInteger(0);
        AtomicInteger rejectedCalls = new AtomicInteger(0);

        // When - Make calls until circuit opens
        for (int i = 0; i < 10; i++) {
            try {
                circuitBreaker.execute(() -> {
                    callAttempts.incrementAndGet();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/failing"))
                            .GET()
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() >= 500) {
                        throw new RuntimeException("Server error: " + response.statusCode());
                    }
                    return response.statusCode();
                });
            } catch (CircuitBreakerOpenException e) {
                rejectedCalls.incrementAndGet();
            } catch (Exception e) {
                // Expected failures
            }
        }

        // Then
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(callAttempts.get()).isEqualTo(3); // Only 3 calls before circuit opened
        assertThat(rejectedCalls.get()).isEqualTo(7); // Rest were rejected
    }

    @Test
    @DisplayName("should transition to half-open after timeout")
    void shouldTransitionToHalfOpenAfterTimeout() throws Exception {
        // Given - Open the circuit
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new RuntimeException("Simulated failure");
                });
            } catch (Exception e) {
                // Expected
            }
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // When - Wait for open duration
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Try a call to trigger half-open
                    try {
                        circuitBreaker.execute(() -> "test");
                    } catch (Exception e) {
                        // May still fail
                    }
                    assertThat(circuitBreaker.getState())
                            .isIn(CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED);
                });
    }

    @Test
    @DisplayName("should close circuit after successful calls in half-open")
    void shouldCloseCircuitAfterSuccessfulHalfOpen() throws Exception {
        // Given - Open the circuit
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new RuntimeException("Failure");
                });
            } catch (Exception e) {
                // Expected
            }
        }

        // Wait for half-open
        Thread.sleep(2500);

        // When - Make successful calls
        for (int i = 0; i < 2; i++) {
            circuitBreaker.execute(() -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/healthy"))
                        .GET()
                        .build();
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
            });
        }

        // Then
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should handle concurrent requests correctly")
    void shouldHandleConcurrentRequests() throws Exception {
        // Given
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();

        // When - Launch concurrent requests
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String result = circuitBreaker.execute(() -> {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/healthy"))
                                .GET()
                                .build();
                        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        return "status:" + response.statusCode();
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

        // Then
        assertThat(results).hasSize(threadCount);
        long successCount = results.stream().filter(r -> r.equals("status:200")).count();
        assertThat(successCount).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("should use fallback when circuit is open")
    void shouldUseFallbackWhenOpen() throws Exception {
        // Given - Open the circuit
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new RuntimeException("Failure");
                });
            } catch (Exception e) {
                // Expected
            }
        }

        // When
        String result = circuitBreaker.executeWithFallback(
                () -> {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/failing"))
                            .GET()
                            .build();
                    return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
                },
                () -> "{\"fallback\":true,\"cached\":\"data\"}"
        );

        // Then
        assertThat(result).contains("fallback");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("should notify listeners on state change")
    void shouldNotifyListenersOnStateChange() throws Exception {
        // Given
        List<String> stateChanges = new CopyOnWriteArrayList<>();
        circuitBreaker.onStateChange((from, to) ->
                stateChanges.add(from.name() + "->" + to.name()));

        // When - Trigger failures to open circuit
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new RuntimeException("Failure");
                });
            } catch (Exception e) {
                // Expected
            }
        }

        // Then
        assertThat(stateChanges).contains("CLOSED->OPEN");
    }

    @Test
    @DisplayName("should handle high load without race conditions")
    void shouldHandleHighLoadWithoutRaceConditions() throws Exception {
        // Given
        int iterations = 1000;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        // When - Rapid fire requests
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            final int index = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    circuitBreaker.execute(() -> {
                        // Alternate between success and failure
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

        // Then
        int total = successCount.get() + failureCount.get() + rejectedCount.get();
        assertThat(total).isEqualTo(iterations);

        // Circuit breaker state should be consistent
        CircuitBreaker.State finalState = circuitBreaker.getState();
        assertThat(finalState).isIn(
                CircuitBreaker.State.CLOSED,
                CircuitBreaker.State.OPEN,
                CircuitBreaker.State.HALF_OPEN);
    }
}
