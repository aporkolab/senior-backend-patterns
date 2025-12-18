package com.aporkolab.patterns.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import com.aporkolab.patterns.messaging.dlq.FailureType;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for Dead Letter Queue.
 * 
 * Provides the following metrics:
 * - dlq_messages_total: Messages sent to DLQ by failure type
 * - dlq_retries_total: Retry attempts before DLQ
 * - dlq_reprocess_total: Reprocessed messages from DLQ
 * - dlq_reprocess_success_total: Successfully reprocessed messages
 * - dlq_depth: Current DLQ message count
 * - dlq_processing_duration: Time to process failed messages
 */
public class DlqMetrics {

    private static final String METRIC_PREFIX = "dlq";

    private final MeterRegistry registry;
    private final Tags baseTags;

    private final Counter messagesCounter;
    private final Counter retriesCounter;
    private final Counter reprocessCounter;
    private final Counter reprocessSuccessCounter;
    private final Timer processingTimer;

    private final AtomicLong currentDepth = new AtomicLong(0);
    private final ConcurrentMap<String, AtomicLong> depthByTopic = new ConcurrentHashMap<>();

    public DlqMetrics(MeterRegistry registry) {
        this(registry, Tags.empty());
    }

    public DlqMetrics(MeterRegistry registry, String dlqName) {
        this(registry, Tags.of("dlq_name", dlqName));
    }

    public DlqMetrics(MeterRegistry registry, Tags tags) {
        this.registry = registry;
        this.baseTags = tags;

        // Counters
        this.messagesCounter = Counter.builder(METRIC_PREFIX + "_messages_total")
                .description("Total messages sent to DLQ")
                .tags(baseTags)
                .register(registry);

        this.retriesCounter = Counter.builder(METRIC_PREFIX + "_retries_total")
                .description("Total retry attempts before sending to DLQ")
                .tags(baseTags)
                .register(registry);

        this.reprocessCounter = Counter.builder(METRIC_PREFIX + "_reprocess_total")
                .description("Total reprocess attempts from DLQ")
                .tags(baseTags)
                .register(registry);

        this.reprocessSuccessCounter = Counter.builder(METRIC_PREFIX + "_reprocess_success_total")
                .description("Successfully reprocessed messages from DLQ")
                .tags(baseTags)
                .register(registry);

        // Timer
        this.processingTimer = Timer.builder(METRIC_PREFIX + "_processing_duration")
                .description("Time to process and route failed messages")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // Gauge for total depth
        Gauge.builder(METRIC_PREFIX + "_depth", currentDepth, AtomicLong::get)
                .description("Current number of messages in DLQ")
                .tags(baseTags)
                .register(registry);
    }

    /**
     * Record a message being sent to DLQ.
     */
    public void recordMessageSent(FailureType failureType) {
        messagesCounter.increment();

        // Also track by failure type
        Counter.builder(METRIC_PREFIX + "_messages_by_type_total")
                .tags(baseTags.and("failure_type", failureType.name()))
                .description("Messages sent to DLQ by failure type")
                .register(registry)
                .increment();
    }

    /**
     * Record a message being sent to DLQ from a specific topic.
     */
    public void recordMessageSent(String originalTopic, FailureType failureType) {
        recordMessageSent(failureType);

        Counter.builder(METRIC_PREFIX + "_messages_by_topic_total")
                .tags(baseTags.and("original_topic", originalTopic, "failure_type", failureType.name()))
                .description("Messages sent to DLQ by original topic")
                .register(registry)
                .increment();
    }

    /**
     * Record a retry attempt.
     */
    public void recordRetry() {
        retriesCounter.increment();
    }

    /**
     * Record retry attempt with details.
     */
    public void recordRetry(int attemptNumber, int maxAttempts) {
        retriesCounter.increment();

        Counter.builder(METRIC_PREFIX + "_retry_attempts")
                .tags(baseTags.and("attempt", String.valueOf(attemptNumber)))
                .register(registry)
                .increment();

        // Track final retry (about to go to DLQ)
        if (attemptNumber >= maxAttempts) {
            Counter.builder(METRIC_PREFIX + "_max_retries_reached_total")
                    .tags(baseTags)
                    .register(registry)
                    .increment();
        }
    }

    /**
     * Record a reprocess attempt from DLQ.
     */
    public void recordReprocessAttempt() {
        reprocessCounter.increment();
    }

    /**
     * Record successful reprocess.
     */
    public void recordReprocessSuccess() {
        reprocessSuccessCounter.increment();
    }

    /**
     * Record a failed reprocess.
     */
    public void recordReprocessFailure(String reason) {
        Counter.builder(METRIC_PREFIX + "_reprocess_failure_total")
                .tags(baseTags.and("reason", reason))
                .register(registry)
                .increment();
    }

    /**
     * Time a DLQ processing operation.
     */
    public void recordProcessingTime(Duration duration) {
        processingTimer.record(duration);
    }

    /**
     * Time a processing operation.
     */
    public <T> T timeProcessing(java.util.function.Supplier<T> operation) {
        return processingTimer.record(operation);
    }

    /**
     * Update the total DLQ depth.
     */
    public void updateDepth(long depth) {
        currentDepth.set(depth);
    }

    /**
     * Update DLQ depth for a specific topic.
     */
    public void updateDepthByTopic(String topic, long depth) {
        AtomicLong topicDepth = depthByTopic.computeIfAbsent(topic, t -> {
            AtomicLong counter = new AtomicLong(0);
            Gauge.builder(METRIC_PREFIX + "_depth_by_topic", counter, AtomicLong::get)
                    .tags(baseTags.and("topic", t))
                    .description("DLQ depth by original topic")
                    .register(registry);
            return counter;
        });
        topicDepth.set(depth);
    }

    /**
     * Record complete DLQ flow metrics.
     */
    public void recordDlqFlow(String originalTopic, FailureType failureType, 
                               int retryAttempts, Duration processingTime) {
        recordMessageSent(originalTopic, failureType);
        for (int i = 0; i < retryAttempts; i++) {
            recordRetry();
        }
        recordProcessingTime(processingTime);
    }

    /**
     * Get DLQ success rate for reprocessing.
     */
    public double getReprocessSuccessRate() {
        double total = reprocessCounter.count();
        if (total == 0) return 0.0;
        return (reprocessSuccessCounter.count() / total) * 100.0;
    }

    /**
     * Get failure type distribution.
     */
    public ConcurrentMap<FailureType, Long> getFailureTypeDistribution() {
        ConcurrentMap<FailureType, Long> distribution = new ConcurrentHashMap<>();
        for (FailureType type : FailureType.values()) {
            // This would need to query the registry for actual counts
            distribution.put(type, 0L);
        }
        return distribution;
    }
}
