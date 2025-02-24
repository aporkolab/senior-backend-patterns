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
    
    private final ConcurrentMap<FailureType, Counter> failureTypeCounters = new ConcurrentHashMap<>();

    public DlqMetrics(MeterRegistry registry) {
        this(registry, Tags.empty());
    }

    public DlqMetrics(MeterRegistry registry, String dlqName) {
        this(registry, Tags.of("dlq_name", dlqName));
    }

    public DlqMetrics(MeterRegistry registry, Tags tags) {
        this.registry = registry;
        this.baseTags = tags;

        
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

        
        this.processingTimer = Timer.builder(METRIC_PREFIX + "_processing_duration")
                .description("Time to process and route failed messages")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        
        Gauge.builder(METRIC_PREFIX + "_depth", currentDepth, AtomicLong::get)
                .description("Current number of messages in DLQ")
                .tags(baseTags)
                .register(registry);
    }

    
    public void recordMessageSent(FailureType failureType) {
        messagesCounter.increment();

        
        Counter counter = failureTypeCounters.computeIfAbsent(failureType, ft ->
                Counter.builder(METRIC_PREFIX + "_messages_by_type_total")
                        .tags(baseTags.and("failure_type", ft.name()))
                        .description("Messages sent to DLQ by failure type")
                        .register(registry));
        counter.increment();
    }

    
    public void recordMessageSent(String originalTopic, FailureType failureType) {
        recordMessageSent(failureType);

        Counter.builder(METRIC_PREFIX + "_messages_by_topic_total")
                .tags(baseTags.and("original_topic", originalTopic, "failure_type", failureType.name()))
                .description("Messages sent to DLQ by original topic")
                .register(registry)
                .increment();
    }

    
    public void recordRetry() {
        retriesCounter.increment();
    }

    
    public void recordRetry(int attemptNumber, int maxAttempts) {
        retriesCounter.increment();

        Counter.builder(METRIC_PREFIX + "_retry_attempts")
                .tags(baseTags.and("attempt", String.valueOf(attemptNumber)))
                .register(registry)
                .increment();

        
        if (attemptNumber >= maxAttempts) {
            Counter.builder(METRIC_PREFIX + "_max_retries_reached_total")
                    .tags(baseTags)
                    .register(registry)
                    .increment();
        }
    }

    
    public void recordReprocessAttempt() {
        reprocessCounter.increment();
    }

    
    public void recordReprocessSuccess() {
        reprocessSuccessCounter.increment();
    }

    
    public void recordReprocessFailure(String reason) {
        Counter.builder(METRIC_PREFIX + "_reprocess_failure_total")
                .tags(baseTags.and("reason", reason))
                .register(registry)
                .increment();
    }

    
    public void recordProcessingTime(Duration duration) {
        processingTimer.record(duration);
    }

    
    public <T> T timeProcessing(java.util.function.Supplier<T> operation) {
        return processingTimer.record(operation);
    }

    
    public void updateDepth(long depth) {
        currentDepth.set(depth);
    }

    
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

    
    public void recordDlqFlow(String originalTopic, FailureType failureType, 
                               int retryAttempts, Duration processingTime) {
        recordMessageSent(originalTopic, failureType);
        for (int i = 0; i < retryAttempts; i++) {
            recordRetry();
        }
        recordProcessingTime(processingTime);
    }

    
    public double getReprocessSuccessRate() {
        double total = reprocessCounter.count();
        if (total == 0) return 0.0;
        return (reprocessSuccessCounter.count() / total) * 100.0;
    }

    
    public ConcurrentMap<FailureType, Long> getFailureTypeDistribution() {
        ConcurrentMap<FailureType, Long> distribution = new ConcurrentHashMap<>();
        for (FailureType type : FailureType.values()) {
            Counter counter = failureTypeCounters.get(type);
            distribution.put(type, counter != null ? (long) counter.count() : 0L);
        }
        return distribution;
    }

    
    public long getTotalMessageCount() {
        return (long) messagesCounter.count();
    }

    
    public long getTotalRetryCount() {
        return (long) retriesCounter.count();
    }

    
    public long getTotalReprocessCount() {
        return (long) reprocessCounter.count();
    }

    
    public long getCurrentDepth() {
        return currentDepth.get();
    }

    
    public long getDepthByTopic(String topic) {
        AtomicLong depth = depthByTopic.get(topic);
        return depth != null ? depth.get() : 0L;
    }
}
