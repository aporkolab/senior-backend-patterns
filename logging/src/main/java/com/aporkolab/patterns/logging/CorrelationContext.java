package com.aporkolab.patterns.logging;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.slf4j.MDC;

/**
 * Manages correlation context for distributed tracing via MDC (Mapped Diagnostic Context).
 * 
 * The correlation ID is propagated across:
 * - HTTP requests (via header)
 * - Kafka messages (via header)
 * - Async tasks (via context propagation)
 * 
 * Usage:
 * <pre>
 * // Start new context
 * try (var ctx = CorrelationContext.create()) {
 *     log.info("Processing request"); // Logs include correlationId
 * }
 * 
 * // Continue existing context
 * try (var ctx = CorrelationContext.continueOrCreate(incomingCorrelationId)) {
 *     log.info("Processing message");
 * }
 * </pre>
 */
public class CorrelationContext implements AutoCloseable {

    public static final String CORRELATION_ID_KEY = "correlationId";
    public static final String REQUEST_ID_KEY = "requestId";
    public static final String SPAN_ID_KEY = "spanId";
    public static final String USER_ID_KEY = "userId";
    public static final String SERVICE_NAME_KEY = "service";

    // HTTP Header names
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    private final Map<String, String> previousContext;
    private final boolean ownsContext;

    private CorrelationContext(Map<String, String> previousContext, boolean ownsContext) {
        this.previousContext = previousContext;
        this.ownsContext = ownsContext;
    }

    /**
     * Creates a new correlation context with a generated correlation ID.
     */
    public static CorrelationContext create() {
        return create(generateId());
    }

    /**
     * Creates a new correlation context with the specified correlation ID.
     */
    public static CorrelationContext create(String correlationId) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        
        MDC.put(CORRELATION_ID_KEY, correlationId);
        MDC.put(REQUEST_ID_KEY, generateId());
        MDC.put(SPAN_ID_KEY, generateSpanId());

        return new CorrelationContext(previous, true);
    }

    /**
     * Continues an existing correlation context or creates a new one if not present.
     */
    public static CorrelationContext continueOrCreate(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return create();
        }
        return create(correlationId);
    }

    /**
     * Gets the current correlation ID, or null if not set.
     */
    public static String getCurrentCorrelationId() {
        return MDC.get(CORRELATION_ID_KEY);
    }

    /**
     * Gets the current request ID, or null if not set.
     */
    public static String getCurrentRequestId() {
        return MDC.get(REQUEST_ID_KEY);
    }

    /**
     * Sets additional context values.
     */
    public CorrelationContext with(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
        return this;
    }

    /**
     * Sets the user ID in the context.
     */
    public CorrelationContext withUserId(String userId) {
        return with(USER_ID_KEY, userId);
    }

    /**
     * Sets the service name in the context.
     */
    public CorrelationContext withService(String serviceName) {
        return with(SERVICE_NAME_KEY, serviceName);
    }

    /**
     * Creates a child span with a new span ID.
     */
    public CorrelationContext newSpan() {
        MDC.put(SPAN_ID_KEY, generateSpanId());
        return this;
    }

    /**
     * Wraps a Runnable to propagate the current correlation context.
     */
    public static Runnable wrap(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wraps a Callable to propagate the current correlation context.
     */
    public static <T> Callable<T> wrap(Callable<T> callable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                return callable.call();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    @Override
    public void close() {
        if (ownsContext) {
            if (previousContext != null) {
                MDC.setContextMap(previousContext);
            } else {
                MDC.clear();
            }
        }
    }

    private static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
