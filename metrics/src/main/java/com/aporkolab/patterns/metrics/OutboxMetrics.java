package com.aporkolab.patterns.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Micrometer metrics for Outbox Pattern.
 * 
 * Provides the following metrics:
 * - outbox_events_created_total: Events written to outbox
 * - outbox_events_published_total: Events successfully published
 * - outbox_events_failed_total: Events that failed to publish
 * - outbox_events_pending: Current pending events count
 * - outbox_publish_duration: Time to publish events
 * - outbox_batch_size: Events per processing batch
 * - outbox_lag_seconds: Age of oldest pending event
 */
public class OutboxMetrics {

    private static final String METRIC_PREFIX = "outbox";

    private final MeterRegistry registry;
    private final Tags baseTags;

    private final Counter eventsCreatedCounter;
    private final Counter eventsPublishedCounter;
    private final Counter eventsFailedCounter;
    private final Timer publishTimer;
    private final DistributionSummary batchSizeSummary;

    private final AtomicLong pendingCount = new AtomicLong(0);
    private final AtomicLong oldestEventAgeSeconds = new AtomicLong(0);

    public OutboxMetrics(MeterRegistry registry) {
        this(registry, Tags.empty());
    }

    public OutboxMetrics(MeterRegistry registry, Tags tags) {
        this.registry = registry;
        this.baseTags = tags;

        // Counters
        this.eventsCreatedCounter = Counter.builder(METRIC_PREFIX + "_events_created_total")
                .description("Total events written to outbox")
                .tags(baseTags)
                .register(registry);

        this.eventsPublishedCounter = Counter.builder(METRIC_PREFIX + "_events_published_total")
                .description("Total events successfully published from outbox")
                .tags(baseTags)
                .register(registry);

        this.eventsFailedCounter = Counter.builder(METRIC_PREFIX + "_events_failed_total")
                .description("Total events that failed to publish")
                .tags(baseTags)
                .register(registry);

        // Timer
        this.publishTimer = Timer.builder(METRIC_PREFIX + "_publish_duration")
                .description("Time to publish events from outbox")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // Distribution Summary
        this.batchSizeSummary = DistributionSummary.builder(METRIC_PREFIX + "_batch_size")
                .description("Number of events per processing batch")
                .tags(baseTags)
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        // Gauges
        Gauge.builder(METRIC_PREFIX + "_events_pending", pendingCount, AtomicLong::get)
                .description("Current count of pending outbox events")
                .tags(baseTags)
                .register(registry);

        Gauge.builder(METRIC_PREFIX + "_lag_seconds", oldestEventAgeSeconds, AtomicLong::get)
                .description("Age of oldest pending event in seconds")
                .tags(baseTags)
                .register(registry);
    }

    /**
     * Record an event being written to the outbox.
     */
    public void recordEventCreated() {
        eventsCreatedCounter.increment();
    }

    /**
     * Record an event being written to the outbox with aggregate type tag.
     */
    public void recordEventCreated(String aggregateType) {
        eventsCreatedCounter.increment();
        Counter.builder(METRIC_PREFIX + "_events_by_type_total")
                .tags(baseTags.and("aggregate_type", aggregateType, "status", "created"))
                .register(registry)
                .increment();
    }

    /**
     * Record an event being successfully published.
     */
    public void recordEventPublished() {
        eventsPublishedCounter.increment();
    }

    /**
     * Record an event being successfully published with type.
     */
    public void recordEventPublished(String aggregateType) {
        eventsPublishedCounter.increment();
        Counter.builder(METRIC_PREFIX + "_events_by_type_total")
                .tags(baseTags.and("aggregate_type", aggregateType, "status", "published"))
                .register(registry)
                .increment();
    }

    /**
     * Record a failed publish attempt.
     */
    public void recordEventFailed() {
        eventsFailedCounter.increment();
    }

    /**
     * Record a failed publish attempt with reason.
     */
    public void recordEventFailed(String reason) {
        eventsFailedCounter.increment();
        Counter.builder(METRIC_PREFIX + "_events_failed_by_reason_total")
                .tags(baseTags.and("reason", reason))
                .register(registry)
                .increment();
    }

    /**
     * Record a batch processing cycle.
     */
    public void recordBatch(int batchSize, Duration duration) {
        batchSizeSummary.record(batchSize);
        publishTimer.record(duration);
    }

    /**
     * Time a publishing operation.
     */
    public <T> T timePublish(Supplier<T> operation) {
        return publishTimer.record(operation);
    }

    /**
     * Update the pending events count.
     */
    public void updatePendingCount(long count) {
        pendingCount.set(count);
    }

    /**
     * Update the oldest event age.
     */
    public void updateOldestEventAge(Instant oldestCreatedAt) {
        if (oldestCreatedAt != null) {
            long ageSeconds = Duration.between(oldestCreatedAt, Instant.now()).getSeconds();
            oldestEventAgeSeconds.set(Math.max(0, ageSeconds));
        } else {
            oldestEventAgeSeconds.set(0);
        }
    }

    /**
     * Record metrics for a complete processing cycle.
     */
    public void recordProcessingCycle(int processed, int failed, long remainingPending, 
                                       Instant oldestEventTime, Duration cycleDuration) {
        for (int i = 0; i < processed; i++) {
            recordEventPublished();
        }
        for (int i = 0; i < failed; i++) {
            recordEventFailed();
        }
        recordBatch(processed + failed, cycleDuration);
        updatePendingCount(remainingPending);
        updateOldestEventAge(oldestEventTime);
    }

    /**
     * Get the underlying registry.
     */
    public MeterRegistry getRegistry() {
        return registry;
    }
}
