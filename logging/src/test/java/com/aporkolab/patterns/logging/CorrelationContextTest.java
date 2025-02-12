package com.aporkolab.patterns.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;


class CorrelationContextTest {

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("should generate correlation ID when creating context")
    void shouldGenerateCorrelationId() {
        try (CorrelationContext ctx = CorrelationContext.create()) {
            String correlationId = CorrelationContext.getCurrentCorrelationId();
            assertThat(correlationId).isNotNull();
            assertThat(correlationId).hasSize(16);
        }
    }

    @Test
    @DisplayName("should use provided correlation ID")
    void shouldUseProvidedCorrelationId() {
        String myId = "my-correlation-id";
        try (CorrelationContext ctx = CorrelationContext.create(myId)) {
            assertThat(CorrelationContext.getCurrentCorrelationId()).isEqualTo(myId);
        }
    }

    @Test
    @DisplayName("should generate request ID separately from correlation ID")
    void shouldGenerateRequestId() {
        try (CorrelationContext ctx = CorrelationContext.create()) {
            String correlationId = CorrelationContext.getCurrentCorrelationId();
            String requestId = CorrelationContext.getCurrentRequestId();

            assertThat(requestId).isNotNull();
            assertThat(requestId).isNotEqualTo(correlationId);
        }
    }

    @Test
    @DisplayName("should clear MDC on context close")
    void shouldClearMdcOnClose() {
        try (CorrelationContext ctx = CorrelationContext.create()) {
            assertThat(CorrelationContext.getCurrentCorrelationId()).isNotNull();
        }

        assertThat(CorrelationContext.getCurrentCorrelationId()).isNull();
    }

    @Test
    @DisplayName("should restore previous context on close")
    void shouldRestorePreviousContextOnClose() {
        MDC.put("existingKey", "existingValue");

        try (CorrelationContext ctx = CorrelationContext.create()) {
            assertThat(CorrelationContext.getCurrentCorrelationId()).isNotNull();
        }

        assertThat(MDC.get("existingKey")).isEqualTo("existingValue");
    }

    @Test
    @DisplayName("should continue or create new correlation ID")
    void shouldContinueOrCreateCorrelationId() {
        try (CorrelationContext ctx = CorrelationContext.continueOrCreate("existing-id")) {
            assertThat(CorrelationContext.getCurrentCorrelationId()).isEqualTo("existing-id");
        }

        
        try (CorrelationContext ctx = CorrelationContext.continueOrCreate(null)) {
            assertThat(CorrelationContext.getCurrentCorrelationId()).isNotNull();
            assertThat(CorrelationContext.getCurrentCorrelationId()).hasSize(16);
        }

        
        try (CorrelationContext ctx = CorrelationContext.continueOrCreate("  ")) {
            assertThat(CorrelationContext.getCurrentCorrelationId()).isNotNull();
            assertThat(CorrelationContext.getCurrentCorrelationId()).hasSize(16);
        }
    }

    @Test
    @DisplayName("should add custom context values")
    void shouldAddCustomContextValues() {
        try (CorrelationContext ctx = CorrelationContext.create()) {
            ctx.with("customKey", "customValue");
            assertThat(MDC.get("customKey")).isEqualTo("customValue");
        }
    }

    @Test
    @DisplayName("should add user ID to context")
    void shouldAddUserIdToContext() {
        try (CorrelationContext ctx = CorrelationContext.create()) {
            ctx.withUserId("user-123");
            assertThat(MDC.get(CorrelationContext.USER_ID_KEY)).isEqualTo("user-123");
        }
    }

    @Test
    @DisplayName("should add service name to context")
    void shouldAddServiceNameToContext() {
        try (CorrelationContext ctx = CorrelationContext.create()) {
            ctx.withService("my-service");
            assertThat(MDC.get(CorrelationContext.SERVICE_NAME_KEY)).isEqualTo("my-service");
        }
    }

    @Test
    @DisplayName("should create new span")
    void shouldCreateNewSpan() {
        try (CorrelationContext ctx = CorrelationContext.create()) {
            String firstSpan = MDC.get(CorrelationContext.SPAN_ID_KEY);
            ctx.newSpan();
            String secondSpan = MDC.get(CorrelationContext.SPAN_ID_KEY);

            assertThat(secondSpan).isNotEqualTo(firstSpan);
        }
    }

    @Test
    @DisplayName("should wrap Runnable to propagate context")
    void shouldWrapRunnableToPropagateContext() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String[] capturedId = new String[1];

        try (CorrelationContext ctx = CorrelationContext.create()) {
            String originalId = CorrelationContext.getCurrentCorrelationId();

            Runnable wrapped = CorrelationContext.wrap(() -> {
                capturedId[0] = CorrelationContext.getCurrentCorrelationId();
            });

            executor.submit(wrapped).get();

            assertThat(capturedId[0]).isEqualTo(originalId);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("should wrap Callable to propagate context")
    void shouldWrapCallableToPropagateContext() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (CorrelationContext ctx = CorrelationContext.create()) {
            String originalId = CorrelationContext.getCurrentCorrelationId();

            Callable<String> wrapped = CorrelationContext.wrap(
                    CorrelationContext::getCurrentCorrelationId
            );

            Future<String> future = executor.submit(wrapped);
            String capturedId = future.get();

            assertThat(capturedId).isEqualTo(originalId);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("should not propagate null context values")
    void shouldNotPropagateNullValues() {
        try (CorrelationContext ctx = CorrelationContext.create()) {
            ctx.with("key", null);
            assertThat(MDC.get("key")).isNull();
        }
    }

    @Test
    @DisplayName("should support method chaining")
    void shouldSupportMethodChaining() {
        try (CorrelationContext ctx = CorrelationContext.create()
                .withUserId("user-1")
                .withService("service-1")
                .with("custom", "value")) {

            assertThat(MDC.get(CorrelationContext.USER_ID_KEY)).isEqualTo("user-1");
            assertThat(MDC.get(CorrelationContext.SERVICE_NAME_KEY)).isEqualTo("service-1");
            assertThat(MDC.get("custom")).isEqualTo("value");
        }
    }
}
