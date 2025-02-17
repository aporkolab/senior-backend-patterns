package com.aporkolab.patterns.outbox;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;


@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, String aggregateId, String eventType, Object event) {
        validateParameters(aggregateType, aggregateId, eventType);
        Objects.requireNonNull(event, "event must not be null");

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

    
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishRaw(String aggregateType, String aggregateId, String eventType, String jsonPayload) {
        validateParameters(aggregateType, aggregateId, eventType);
        if (jsonPayload == null || jsonPayload.isBlank()) {
            throw new IllegalArgumentException("jsonPayload must not be null or blank");
        }

        OutboxEvent outboxEvent = new OutboxEvent(aggregateType, aggregateId, eventType, jsonPayload);
        outboxRepository.save(outboxEvent);

        log.debug("Outbox event created (raw): type={}, aggregateId={}, eventType={}",
                aggregateType, aggregateId, eventType);
    }

    private void validateParameters(String aggregateType, String aggregateId, String eventType) {
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be null or blank");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId must not be null or blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be null or blank");
        }
    }
}
