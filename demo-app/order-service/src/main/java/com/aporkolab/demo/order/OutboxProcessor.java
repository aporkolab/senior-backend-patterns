package com.aporkolab.demo.order;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final String TOPIC = "order-events";

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxProcessor(
            OutboxEventRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000) // Poll every second
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> events = outboxRepository.findPendingEventsForUpdate();
        
        if (events.isEmpty()) {
            return;
        }

        log.info("Processing {} outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                publishEvent(event);
                event.setStatus(OutboxEvent.OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                log.debug("Published event: {} - {}", event.getEventType(), event.getAggregateId());
            } catch (Exception e) {
                log.error("Failed to publish event: {}", event.getId(), e);
                event.setRetryCount(event.getRetryCount() + 1);
                if (event.getRetryCount() >= 3) {
                    event.setStatus(OutboxEvent.OutboxStatus.FAILED);
                    log.warn("Event {} marked as FAILED after {} retries", event.getId(), event.getRetryCount());
                }
            }
            outboxRepository.save(event);
        }
    }

    private void publishEvent(OutboxEvent event) {
        String key = event.getAggregateId();
        String value = createEventEnvelope(event);

        CompletableFuture<SendResult<String, String>> future = 
                kafkaTemplate.send(TOPIC, key, value);

        // Wait for completion (with timeout in real scenario)
        future.join();
    }

    private String createEventEnvelope(OutboxEvent event) {
        return String.format("""
                {
                    "eventId": "%s",
                    "eventType": "%s",
                    "aggregateType": "%s",
                    "aggregateId": "%s",
                    "timestamp": "%s",
                    "payload": %s
                }
                """,
                event.getId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getCreatedAt(),
                event.getPayload()
        );
    }
}
