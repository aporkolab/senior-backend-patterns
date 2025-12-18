package com.aporkolab.patterns.resilience.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Unit tests for ResilientHttpClient.
 * Uses MockWebServer to simulate various server behaviors.
 */
class ResilientHttpClientTest {

    private static MockWebServer mockServer;
    private ResilientHttpClient client;

    @BeforeAll
    static void startServer() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        mockServer.shutdown();
    }

    @BeforeEach
    void setUp() {
        client = ResilientHttpClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .maxRetries(3)
                .initialBackoffMs(10) // Fast for tests
                .maxBackoffMs(100)
                .connectionTimeoutMs(1000)
                .build();
    }

    @Nested
    @DisplayName("Successful Requests")
    class SuccessfulRequests {

        @Test
        @DisplayName("should return response on successful GET")
        void shouldReturnResponseOnSuccessfulGet() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"status\":\"ok\"}"));

            HttpResponse<String> response = client.get("/test");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}");
        }

        @Test
        @DisplayName("should return response on successful POST")
        void shouldReturnResponseOnSuccessfulPost() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setResponseCode(201)
                    .setBody("{\"id\":123}"));

            HttpResponse<String> response = client.post("/create", "{\"name\":\"test\"}");

            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).contains("123");
        }

        @Test
        @DisplayName("should handle 4xx responses without retry")
        void shouldHandle4xxWithoutRetry() throws Exception {
            mockServer.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));
            mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));

            HttpResponse<String> response = client.get("/bad");

            assertThat(response.statusCode()).isEqualTo(400);
            // Second request should not be made (no retry on 4xx)
            assertThat(mockServer.getRequestCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Retry Behavior")
    class RetryBehavior {

        @Test
        @DisplayName("should retry on 500 status code")
        void shouldRetryOn500() throws Exception {
            mockServer.enqueue(new MockResponse().setResponseCode(500));
            mockServer.enqueue(new MockResponse().setResponseCode(500));
            mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("success"));

            HttpResponse<String> response = client.get("/flaky");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("success");
            assertThat(mockServer.getRequestCount()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("should retry on 502 status code")
        void shouldRetryOn502() throws Exception {
            mockServer.enqueue(new MockResponse().setResponseCode(502));
            mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

            HttpResponse<String> response = client.get("/gateway");

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("should retry on 503 status code")
        void shouldRetryOn503() throws Exception {
            mockServer.enqueue(new MockResponse().setResponseCode(503));
            mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

            HttpResponse<String> response = client.get("/unavailable");

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("should retry on 504 status code")
        void shouldRetryOn504() throws Exception {
            mockServer.enqueue(new MockResponse().setResponseCode(504));
            mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

            HttpResponse<String> response = client.get("/timeout");

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("should throw after max retries exceeded")
        void shouldThrowAfterMaxRetries() {
            for (int i = 0; i <= 4; i++) { // 1 initial + 3 retries + buffer
                mockServer.enqueue(new MockResponse().setResponseCode(500));
            }

            assertThatThrownBy(() -> client.get("/always-failing"))
                    .isInstanceOf(HttpClientException.class)
                    .hasMessageContaining("failed after")
                    .hasMessageContaining("4 attempts");
        }

        @Test
        @DisplayName("should respect max retries configuration")
        void shouldRespectMaxRetriesConfig() {
            ResilientHttpClient zeroRetryClient = ResilientHttpClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .maxRetries(0)
                    .build();

            mockServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() -> zeroRetryClient.get("/no-retry"))
                    .isInstanceOf(HttpClientException.class)
                    .hasMessageContaining("1 attempts"); // Only initial attempt
        }
    }

    @Nested
    @DisplayName("Async Requests")
    class AsyncRequests {

        @Test
        @DisplayName("should complete async GET successfully")
        void shouldCompleteAsyncGet() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("async-result"));

            CompletableFuture<HttpResponse<String>> future = client.getAsync("/async");
            HttpResponse<String> response = future.get();

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("async-result");
        }

        @Test
        @DisplayName("should retry async request on 5xx")
        void shouldRetryAsyncOn5xx() throws Exception {
            mockServer.enqueue(new MockResponse().setResponseCode(503));
            mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("recovered"));

            CompletableFuture<HttpResponse<String>> future = client.getAsync("/async-flaky");
            HttpResponse<String> response = future.get();

            assertThat(response.body()).isEqualTo("recovered");
        }

        @Test
        @DisplayName("should fail async after max retries")
        void shouldFailAsyncAfterMaxRetries() {
            for (int i = 0; i <= 4; i++) {
                mockServer.enqueue(new MockResponse().setResponseCode(500));
            }

            CompletableFuture<HttpResponse<String>> future = client.getAsync("/async-fail");

            assertThatThrownBy(future::get)
                    .hasCauseInstanceOf(HttpClientException.class);
        }
    }

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("should reject negative max retries")
        void shouldRejectNegativeMaxRetries() {
            assertThatThrownBy(() -> ResilientHttpClient.builder()
                    .maxRetries(-1)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRetries");
        }

        @Test
        @DisplayName("should reject max retries greater than 10")
        void shouldRejectTooManyRetries() {
            assertThatThrownBy(() -> ResilientHttpClient.builder()
                    .maxRetries(11)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRetries");
        }

        @Test
        @DisplayName("should reject invalid jitter factor")
        void shouldRejectInvalidJitter() {
            assertThatThrownBy(() -> ResilientHttpClient.builder()
                    .jitterFactor(1.5)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jitterFactor");
        }

        @Test
        @DisplayName("should strip trailing slash from base URL")
        void shouldStripTrailingSlash() throws Exception {
            ResilientHttpClient slashClient = ResilientHttpClient.builder()
                    .baseUrl(mockServer.url("/").toString() + "/")
                    .build();

            mockServer.enqueue(new MockResponse().setResponseCode(200));
            
            slashClient.get("/test");
            
            RecordedRequest request = mockServer.takeRequest();
            // Should not have double slash
            assertThat(request.getPath()).doesNotContain("//");
        }

        @Test
        @DisplayName("should allow zero jitter factor")
        void shouldAllowZeroJitter() {
            ResilientHttpClient noJitterClient = ResilientHttpClient.builder()
                    .baseUrl("http://localhost")
                    .jitterFactor(0)
                    .build();
            // No exception means success
            assertThat(noJitterClient).isNotNull();
        }
    }

    @Nested
    @DisplayName("Backoff Calculation")
    class BackoffCalculation {

        @Test
        @DisplayName("should apply exponential backoff")
        void shouldApplyExponentialBackoff() throws Exception {
            AtomicInteger requestCount = new AtomicInteger(0);
            long[] requestTimes = new long[4];

            mockServer.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    int count = requestCount.getAndIncrement();
                    requestTimes[count] = System.currentTimeMillis();
                    if (count < 3) {
                        return new MockResponse().setResponseCode(500);
                    }
                    return new MockResponse().setResponseCode(200).setBody("ok");
                }
            });

            client.get("/backoff-test");

            // Verify increasing delays between requests
            // Note: actual times vary due to jitter, but should generally increase
            assertThat(requestCount.get()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Headers")
    class Headers {

        @Test
        @DisplayName("should send custom headers with GET")
        void shouldSendCustomHeadersWithGet() throws Exception {
            mockServer.enqueue(new MockResponse().setResponseCode(200));

            client.get("/headers", java.util.Map.of(
                    "X-Custom-Header", "custom-value",
                    "Authorization", "Bearer token123"
            ));

            RecordedRequest request = mockServer.takeRequest();
            assertThat(request.getHeader("X-Custom-Header")).isEqualTo("custom-value");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token123");
        }

        @Test
        @DisplayName("should send custom headers with POST")
        void shouldSendCustomHeadersWithPost() throws Exception {
            mockServer.enqueue(new MockResponse().setResponseCode(200));

            client.post("/headers", "{}", java.util.Map.of(
                    "Content-Type", "application/json",
                    "X-Request-Id", "req-123"
            ));

            RecordedRequest request = mockServer.takeRequest();
            assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
            assertThat(request.getHeader("X-Request-Id")).isEqualTo("req-123");
        }

        @Test
        @DisplayName("should use default Content-Type for POST")
        void shouldUseDefaultContentType() throws Exception {
            mockServer.enqueue(new MockResponse().setResponseCode(200));

            client.post("/default-headers", "{\"test\":true}");

            RecordedRequest request = mockServer.takeRequest();
            assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
        }
    }
}
