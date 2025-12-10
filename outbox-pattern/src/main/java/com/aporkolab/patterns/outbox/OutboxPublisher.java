package com.aporkolab.patterns.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes events to the outbox table within the current transaction.
 * 
 * Usage:
 * 1. Call publish() within your @Transactional service method
 * 2. Event is written to outbox_events table in the same transaction
 * 3. OutboxProcessor polls and publishes to Kafka
 * 
 * This guarantees atomicity: if the business operation fails, no event is written.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish an event to the outbox (writes to DB, not Kafka).
     * 
     * @param aggregateType The type of aggregate (e.g., "Order", "Payment")
     * @param aggregateId   The ID of the aggregate
     * @param eventType     The type of event (e.g., "OrderCreated", "PaymentCompleted")
     * @param event         The event payload object (will be serialized to JSON)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, String aggregateId, String eventType, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent(aggregateType, aggregateId, eventType, payload);
            outboxRepository.save(outboxEvent);

            log.debug("Outbox event created: type={}, aggregateId={}, eventType={}",
                    aggregateType, aggregateId, eventType);

        } catch (JsonProcessingException e) {
            throw new OutboxException("Failed to serialize event payload", e);
        }
    }

    /**
     * Publish with pre-serialized payload (when you already have JSON).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishRaw(String aggregateType, String aggregateId, String eventType, String jsonPayload) {
        OutboxEvent outboxEvent = new OutboxEvent(aggregateType, aggregateId, eventType, jsonPayload);
        outboxRepository.save(outboxEvent);

        log.debug("Outbox event created (raw): type={}, aggregateId={}, eventType={}",
                aggregateType, aggregateId, eventType);
    }
}
