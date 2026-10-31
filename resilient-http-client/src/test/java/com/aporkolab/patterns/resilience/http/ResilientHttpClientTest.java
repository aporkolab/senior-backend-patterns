package com.aporkolab.patterns.resilience.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;


class ResilientHttpClientTest {

    @Nested
    @DisplayName("Successful Requests")
    class SuccessfulRequests {

        private MockWebServer mockServer;
        private ResilientHttpClient client;

        @BeforeEach
        void setUp() throws IOException {
            mockServer = new MockWebServer();
            mockServer.start();
            client = ResilientHttpClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .maxRetries(3)
                    .initialBackoffMs(10)
                    .maxBackoffMs(100)
                    .connectionTimeoutMs(1000)
                    .build();
        }

        @AfterEach
        void tearDown() {
            mockServer.close();
        }

        @Test
        @DisplayName("should return response on successful GET")
        void shouldReturnResponseOnSuccessfulGet() throws Exception {
            mockServer.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body("{\"status\":\"ok\"}").build());

            HttpResponse<String> response = client.get("/test");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}");
        }

        @Test
        @DisplayName("should return response on successful POST")
        void shouldReturnResponseOnSuccessfulPost() throws Exception {
            mockServer.enqueue(new MockResponse.Builder()
                    .code(201)
                    .body("{\"id\":123}").build());

            HttpResponse<String> response = client.post("/create", "{\"name\":\"test\"}");

            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).contains("123");
        }

        @Test
        @DisplayName("should handle 4xx responses without retry")
        void shouldHandle4xxWithoutRetry() throws Exception {
            mockServer.enqueue(new MockResponse.Builder().code(400).body("Bad Request").build());
            mockServer.enqueue(new MockResponse.Builder().code(200).body("OK").build());

            HttpResponse<String> response = client.get("/bad");

            assertThat(response.statusCode()).isEqualTo(400);
            
            assertThat(mockServer.getRequestCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Retry Behavior")
    class RetryBehavior {

        private MockWebServer mockServer;
        private ResilientHttpClient client;

        @BeforeEach
        void setUp() throws IOException {
            mockServer = new MockWebServer();
            mockServer.start();
            client = ResilientHttpClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .maxRetries(3)
                    .initialBackoffMs(10)
                    .maxBackoffMs(100)
                    .connectionTimeoutMs(1000)
                    .build();
        }

        @AfterEach
        void tearDown() {
            mockServer.close();
        }

        @Test
        @DisplayName("should retry on 500 status code")
        void shouldRetryOn500() throws Exception {
            mockServer.enqueue(new MockResponse.Builder().code(500).build());
            mockServer.enqueue(new MockResponse.Builder().code(500).build());
            mockServer.enqueue(new MockResponse.Builder().code(200).body("success").build());

            HttpResponse<String> response = client.get("/flaky");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("success");
            assertThat(mockServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should retry on 502 status code")
        void shouldRetryOn502() throws Exception {
            mockServer.enqueue(new MockResponse.Builder().code(502).build());
            mockServer.enqueue(new MockResponse.Builder().code(200).body("ok").build());

            HttpResponse<String> response = client.get("/gateway");

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("should retry on 503 status code")
        void shouldRetryOn503() throws Exception {
            mockServer.enqueue(new MockResponse.Builder().code(503).build());
            mockServer.enqueue(new MockResponse.Builder().code(200).body("ok").build());

            HttpResponse<String> response = client.get("/unavailable");

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("should retry on 504 status code")
        void shouldRetryOn504() throws Exception {
            mockServer.enqueue(new MockResponse.Builder().code(504).build());
            mockServer.enqueue(new MockResponse.Builder().code(200).body("ok").build());

            HttpResponse<String> response = client.get("/timeout");

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("should throw after max retries exceeded")
        void shouldThrowAfterMaxRetries() {
            for (int i = 0; i <= 4; i++) { 
                mockServer.enqueue(new MockResponse.Builder().code(500).build());
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

            mockServer.enqueue(new MockResponse.Builder().code(500).build());

            assertThatThrownBy(() -> zeroRetryClient.get("/no-retry"))
                    .isInstanceOf(HttpClientException.class)
                    .hasMessageContaining("1 attempts"); 
        }
    }

    @Nested
    @DisplayName("Async Requests")
    class AsyncRequests {

        private MockWebServer mockServer;
        private ResilientHttpClient client;

        @BeforeEach
        void setUp() throws IOException {
            mockServer = new MockWebServer();
            mockServer.start();
            client = ResilientHttpClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .maxRetries(3)
                    .initialBackoffMs(10)
                    .maxBackoffMs(100)
                    .connectionTimeoutMs(1000)
                    .build();
        }

        @AfterEach
        void tearDown() {
            mockServer.close();
        }

        @Test
        @DisplayName("should complete async GET successfully")
        void shouldCompleteAsyncGet() throws Exception {
            mockServer.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body("async-result").build());

            CompletableFuture<HttpResponse<String>> future = client.getAsync("/async");
            HttpResponse<String> response = future.get();

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("async-result");
        }

        @Test
        @DisplayName("should retry async request on 5xx")
        void shouldRetryAsyncOn5xx() throws Exception {
            mockServer.enqueue(new MockResponse.Builder().code(503).build());
            mockServer.enqueue(new MockResponse.Builder().code(200).body("recovered").build());

            CompletableFuture<HttpResponse<String>> future = client.getAsync("/async-flaky");
            HttpResponse<String> response = future.get();

            assertThat(response.body()).isEqualTo("recovered");
        }

        @Test
        @DisplayName("should fail async after max retries")
        void shouldFailAsyncAfterMaxRetries() {
            for (int i = 0; i <= 4; i++) {
                mockServer.enqueue(new MockResponse.Builder().code(500).build());
            }

            CompletableFuture<HttpResponse<String>> future = client.getAsync("/async-fail");

            assertThatThrownBy(future::get)
                    .hasCauseInstanceOf(HttpClientException.class);
        }
    }

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        private MockWebServer mockServer;

        @BeforeEach
        void setUp() throws IOException {
            mockServer = new MockWebServer();
            mockServer.start();
        }

        @AfterEach
        void tearDown() {
            mockServer.close();
        }

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

            mockServer.enqueue(new MockResponse.Builder().code(200).build());

            slashClient.get("/test");

            RecordedRequest request = mockServer.takeRequest();
            
            assertThat(request.getTarget()).doesNotContain("//");
        }

        @Test
        @DisplayName("should allow zero jitter factor")
        void shouldAllowZeroJitter() {
            ResilientHttpClient noJitterClient = ResilientHttpClient.builder()
                    .baseUrl("http://localhost")
                    .jitterFactor(0)
                    .build();
            
            assertThat(noJitterClient).isNotNull();
        }
    }

    @Nested
    @DisplayName("Backoff Calculation")
    class BackoffCalculation {

        @Test
        @DisplayName("should apply exponential backoff")
        void shouldApplyExponentialBackoff() throws Exception {
            MockWebServer customServer = new MockWebServer();
            customServer.start();
            try {
                AtomicInteger requestCount = new AtomicInteger(0);
                long[] requestTimes = new long[4];

                customServer.setDispatcher(new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        int count = requestCount.getAndIncrement();
                        if (count < requestTimes.length) {
                            requestTimes[count] = System.currentTimeMillis();
                        }
                        if (count < 3) {
                            return new MockResponse.Builder().code(500).build();
                        }
                        return new MockResponse.Builder().code(200).body("ok").build();
                    }
                });

                ResilientHttpClient backoffClient = ResilientHttpClient.builder()
                        .baseUrl(customServer.url("/").toString())
                        .maxRetries(3)
                        .initialBackoffMs(10)
                        .maxBackoffMs(100)
                        .connectionTimeoutMs(1000)
                        .build();

                backoffClient.get("/backoff-test");

                
                assertThat(requestCount.get()).isEqualTo(4);
            } finally {
                customServer.close();
            }
        }
    }

    @Nested
    @DisplayName("Headers")
    class Headers {

        private MockWebServer mockServer;
        private ResilientHttpClient client;

        @BeforeEach
        void setUp() throws IOException {
            mockServer = new MockWebServer();
            mockServer.start();
            client = ResilientHttpClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .maxRetries(3)
                    .initialBackoffMs(10)
                    .maxBackoffMs(100)
                    .connectionTimeoutMs(1000)
                    .build();
        }

        @AfterEach
        void tearDown() {
            mockServer.close();
        }

        @Test
        @DisplayName("should send custom headers with GET")
        void shouldSendCustomHeadersWithGet() throws Exception {
            mockServer.enqueue(new MockResponse.Builder().code(200).build());

            client.get("/headers", java.util.Map.of(
                    "X-Custom-Header", "custom-value",
                    "Authorization", "Bearer token123"
            ));

            RecordedRequest request = mockServer.takeRequest();
            assertThat(request.getHeaders().get("X-Custom-Header")).isEqualTo("custom-value");
            assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer token123");
        }

        @Test
        @DisplayName("should send custom headers with POST")
        void shouldSendCustomHeadersWithPost() throws Exception {
            mockServer.enqueue(new MockResponse.Builder().code(200).build());

            client.post("/headers", "{}", java.util.Map.of(
                    "Content-Type", "application/json",
                    "X-Request-Id", "req-123"
            ));

            RecordedRequest request = mockServer.takeRequest();
            assertThat(request.getHeaders().get("Content-Type")).isEqualTo("application/json");
            assertThat(request.getHeaders().get("X-Request-Id")).isEqualTo("req-123");
        }

        @Test
        @DisplayName("should use default Content-Type for POST")
        void shouldUseDefaultContentType() throws Exception {
            mockServer.enqueue(new MockResponse.Builder().code(200).build());

            client.post("/default-headers", "{\"test\":true}");

            RecordedRequest request = mockServer.takeRequest();
            assertThat(request.getHeaders().get("Content-Type")).isEqualTo("application/json");
        }
    }
}
