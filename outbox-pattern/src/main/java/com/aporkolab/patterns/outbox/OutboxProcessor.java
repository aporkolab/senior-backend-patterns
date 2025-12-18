package com.aporkolab.patterns.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int BATCH_SIZE = 100;
    private static final int RETENTION_DAYS = 7;

    private final OutboxRepository outboxRepository;
    private final KafkaOperations<String, String> kafkaOperations;

    public OutboxProcessor(OutboxRepository outboxRepository, KafkaOperations<String, String> kafkaOperations) {
        this.outboxRepository = outboxRepository;
        this.kafkaOperations = kafkaOperations;
    }

    
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void processOutbox() {
        List<OutboxEvent> pendingEvents = outboxRepository.findPendingEventsForUpdate(BATCH_SIZE);

        if (pendingEvents == null || pendingEvents.isEmpty()) {
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

            } catch (OutboxException e) {
                
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
                event.markFailed();
                outboxRepository.save(event);
            } catch (RuntimeException e) {
                
                log.error("Unexpected error publishing outbox event {}", event.getId(), e);
                event.markFailed();
                outboxRepository.save(event);
            }
        }
    }

    private void publishToKafka(OutboxEvent event) {
        String topic = event.getTopicName();
        String key = event.getAggregateId();
        String value = event.getPayload();

        
        try {
            kafkaOperations.send(topic, key, value).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboxException("Kafka publish interrupted for event " + event.getId(), e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new OutboxException("Kafka publish failed for event " + event.getId(), e.getCause());
        }
    }

    
    @Scheduled(cron = "0 0 3 * * *") 
    @Transactional
    public void cleanupOldEvents() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = outboxRepository.deletePublishedEventsBefore(cutoff);
        log.info("Cleaned up {} old outbox events (before {})", deleted, cutoff);
    }

    
    @Transactional
    public void retryFailedEvents() {
        List<OutboxEvent> failedEvents = outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.FAILED);

        if (failedEvents == null || failedEvents.isEmpty()) {
            log.debug("No failed outbox events to retry");
            return;
        }

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

    
    public long getPendingCount() {
        return outboxRepository.countByStatus(OutboxStatus.PENDING);
    }

    
    public long getFailedCount() {
        return outboxRepository.countByStatus(OutboxStatus.FAILED);
    }
}
