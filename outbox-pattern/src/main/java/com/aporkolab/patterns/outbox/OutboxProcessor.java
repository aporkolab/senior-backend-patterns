package com.aporkolab.patterns.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox table and publishes pending events to Kafka.
 * 
 * Design decisions:
 * - Uses database locking (SKIP LOCKED) for multi-instance safety
 * - Processes in batches to balance throughput and latency
 * - Marks events as published only after Kafka acknowledgment
 * - Includes cleanup of old published events
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int BATCH_SIZE = 100;
    private static final int RETENTION_DAYS = 7;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxProcessor(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Poll and publish pending events every 500ms.
     * Adjust frequency based on latency requirements.
     */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void processOutbox() {
        List<OutboxEvent> pendingEvents = outboxRepository.findPendingEventsForUpdate(BATCH_SIZE);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Processing {} pending outbox events", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                publishToKafka(event);
                event.markPublished();
                outboxRepository.save(event);

                log.debug("Published outbox event: id={}, type={}, topic={}",
                        event.getId(), event.getEventType(), event.getTopicName());

            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
                event.markFailed();
                outboxRepository.save(event);
            }
        }
    }

    private void publishToKafka(OutboxEvent event) {
        String topic = event.getTopicName();
        String key = event.getAggregateId();
        String value = event.getPayload();

        // Synchronous send to ensure delivery before marking as published
        try {
            kafkaTemplate.send(topic, key, value).get();
        } catch (Exception e) {
            throw new OutboxException("Kafka publish failed for event " + event.getId(), e);
        }
    }

    /**
     * Cleanup old published events daily.
     */
    @Scheduled(cron = "0 0 3 * * *") // 3 AM daily
    @Transactional
    public void cleanupOldEvents() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = outboxRepository.deletePublishedEventsBefore(cutoff);
        log.info("Cleaned up {} old outbox events (before {})", deleted, cutoff);
    }

    /**
     * Retry failed events (call manually or schedule).
     */
    @Transactional
    public void retryFailedEvents() {
        List<OutboxEvent> failedEvents = outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.FAILED);

        log.info("Retrying {} failed outbox events", failedEvents.size());

        for (OutboxEvent event : failedEvents) {
            try {
                publishToKafka(event);
                event.markPublished();
                outboxRepository.save(event);
                log.info("Successfully retried outbox event: {}", event.getId());
            } catch (Exception e) {
                log.warn("Retry failed for outbox event {}: {}", event.getId(), e.getMessage());
            }
        }
    }

    /**
     * Get pending event count (for monitoring/alerting).
     */
    public long getPendingCount() {
        return outboxRepository.countByStatus(OutboxStatus.PENDING);
    }

    /**
     * Get failed event count (for monitoring/alerting).
     */
    public long getFailedCount() {
        return outboxRepository.countByStatus(OutboxStatus.FAILED);
    }
}
