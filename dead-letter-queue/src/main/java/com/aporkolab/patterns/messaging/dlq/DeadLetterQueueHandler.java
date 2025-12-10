package com.aporkolab.patterns.messaging.dlq;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Handles failed message routing to Dead Letter Queue.
 * 
 * Design decisions:
 * - Enriches failed messages with full context for debugging
 * - Supports both immediate DLQ (permanent failures) and retry-then-DLQ
 * - Tracks attempt counts in memory (consider Redis for distributed scenarios)
 * - Preserves original message headers
 */
@Component
public class DeadLetterQueueHandler {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueHandler.class);
    private static final String DLQ_SUFFIX = ".dlq";
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String hostname;

    // Track retry attempts per message (key = topic:partition:offset)
    private final Map<String, AtomicInteger> attemptTracker = new ConcurrentHashMap<>();

    public DeadLetterQueueHandler(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.hostname = System.getenv().getOrDefault("HOSTNAME", "unknown");
    }

    /**
     * Handle a processing failure with automatic retry/DLQ decision.
     */
    public void handleFailure(ConsumerRecord<String, String> record, Exception exception) {
        handleFailure(record, exception, DEFAULT_MAX_RETRIES);
    }

    public void handleFailure(ConsumerRecord<String, String> record, Exception exception, int maxRetries) {
        String messageKey = buildMessageKey(record);
        int attempts = attemptTracker
                .computeIfAbsent(messageKey, k -> new AtomicInteger(0))
                .incrementAndGet();

        if (attempts >= maxRetries) {
            log.warn("Max retries ({}) exceeded for message {}, sending to DLQ", maxRetries, messageKey);
            sendToDlq(record, exception, FailureType.MAX_RETRIES_EXCEEDED);
            attemptTracker.remove(messageKey);
        } else {
            log.info("Retry {}/{} for message {}", attempts, maxRetries, messageKey);
            // Let the consumer retry (by not committing the offset)
            throw new RetryableException("Retry attempt " + attempts, exception);
        }
    }

    /**
     * Send a message directly to DLQ (for permanent failures).
     */
    public void sendToDlq(ConsumerRecord<String, String> record, Exception exception, FailureType failureType) {
        String dlqTopic = record.topic() + DLQ_SUFFIX;

        try {
            DlqMessage dlqMessage = DlqMessage.builder()
                    .originalTopic(record.topic())
                    .originalPartition(record.partition())
                    .originalOffset(record.offset())
                    .originalKey(record.key())
                    .originalValue(record.value())
                    .originalTimestamp(Instant.ofEpochMilli(record.timestamp()))
                    .failureReason(exception.getClass().getSimpleName() + ": " + exception.getMessage())
                    .failureType(failureType)
                    .failedAt(Instant.now())
                    .hostname(hostname)
                    .stackTrace(getStackTrace(exception))
                    .build();

            String dlqPayload = objectMapper.writeValueAsString(dlqMessage);

            ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(
                    dlqTopic,
                    record.key(),
                    dlqPayload
            );

            // Preserve original headers
            record.headers().forEach(header ->
                    dlqRecord.headers().add(header.key(), header.value())
            );

            // Add DLQ-specific headers
            dlqRecord.headers().add("dlq-reason", failureType.name().getBytes());
            dlqRecord.headers().add("dlq-timestamp", Instant.now().toString().getBytes());

            kafkaTemplate.send(dlqRecord)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send message to DLQ topic {}: {}", dlqTopic, ex.getMessage());
                        } else {
                            log.info("Message sent to DLQ: topic={}, partition={}, offset={}",
                                    dlqTopic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                        }
                    });

        } catch (Exception e) {
            log.error("Critical: Failed to serialize DLQ message for topic {}: {}",
                    record.topic(), e.getMessage());
            // In production, consider a fallback (e.g., write to local file, alert)
        }
    }

    /**
     * Reprocess messages from DLQ with optional transformation.
     */
    public void reprocessFromDlq(String dlqTopic, Function<DlqMessage, String> transformer, int maxAttempts) {
        // Implementation would:
        // 1. Consume from DLQ topic
        // 2. Apply transformer to fix known issues
        // 3. Republish to original topic
        // 4. Track reprocess attempts
        // 5. Move to "permanent-dlq" after maxAttempts

        log.info("Reprocessing from {} with max {} attempts", dlqTopic, maxAttempts);
        // Actual implementation depends on your Kafka consumer setup
    }

    /**
     * Clear retry tracker entry (call after successful processing).
     */
    public void clearAttempts(ConsumerRecord<String, String> record) {
        attemptTracker.remove(buildMessageKey(record));
    }

    private String buildMessageKey(ConsumerRecord<String, String> record) {
        return String.format("%s:%d:%d", record.topic(), record.partition(), record.offset());
    }

    private String getStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            if (sb.length() > 2000) {
                sb.append("...(truncated)");
                break;
            }
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
