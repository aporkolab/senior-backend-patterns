package com.aporkolab.patterns.messaging.dlq;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;


@Component
public class DeadLetterQueueHandler {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueHandler.class);
    private static final String DLQ_SUFFIX = ".dlq";
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long ATTEMPT_TTL_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);

    private final KafkaOperations<String, String> kafkaOperations;
    private final ObjectMapper objectMapper;
    private final String hostname;

    
    private final Map<String, AttemptEntry> attemptTracker = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler;

    
    private static class AttemptEntry {
        final AtomicInteger attempts = new AtomicInteger(0);
        volatile long lastAttemptTime = System.currentTimeMillis();

        int incrementAndGet() {
            lastAttemptTime = System.currentTimeMillis();
            return attempts.incrementAndGet();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - lastAttemptTime > ATTEMPT_TTL_MS;
        }
    }

    public DeadLetterQueueHandler(KafkaOperations<String, String> kafkaOperations, ObjectMapper objectMapper) {
        this.kafkaOperations = kafkaOperations;
        this.objectMapper = objectMapper;
        this.hostname = System.getenv().getOrDefault("HOSTNAME", "unknown");

        
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dlq-attempt-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupScheduler.scheduleAtFixedRate(
                this::cleanupExpiredAttempts,
                CLEANUP_INTERVAL_MS,
                CLEANUP_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    
    private void cleanupExpiredAttempts() {
        int removed = 0;
        Iterator<Map.Entry<String, AttemptEntry>> it = attemptTracker.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Cleaned up {} expired attempt tracker entries", removed);
        }
    }

    
    public void handleFailure(ConsumerRecord<String, String> record, Exception exception) {
        handleFailure(record, exception, DEFAULT_MAX_RETRIES);
    }

    public void handleFailure(ConsumerRecord<String, String> record, Exception exception, int maxRetries) {
        String messageKey = buildMessageKey(record);
        int attempts = attemptTracker
                .computeIfAbsent(messageKey, k -> new AttemptEntry())
                .incrementAndGet();

        if (attempts >= maxRetries) {
            log.warn("Max retries ({}) exceeded for message {}, sending to DLQ", maxRetries, messageKey);
            sendToDlq(record, exception, FailureType.MAX_RETRIES_EXCEEDED);
            attemptTracker.remove(messageKey);
        } else {
            log.info("Retry {}/{} for message {}", attempts, maxRetries, messageKey);
            
            throw new RetryableException("Retry attempt " + attempts, exception);
        }
    }

    
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

            
            record.headers().forEach(header ->
                    dlqRecord.headers().add(header.key(), header.value())
            );

            
            dlqRecord.headers().add("dlq-reason", failureType.name().getBytes());
            dlqRecord.headers().add("dlq-timestamp", Instant.now().toString().getBytes());

            kafkaOperations.send(dlqRecord)
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
            
        }
    }

    
    public void reprocessFromDlq(String dlqTopic, Function<DlqMessage, String> transformer, int maxAttempts) {
        
        
        
        
        
        

        log.info("Reprocessing from {} with max {} attempts", dlqTopic, maxAttempts);
        
    }

    
    public void clearAttempts(ConsumerRecord<String, String> record) {
        attemptTracker.remove(buildMessageKey(record));
    }

    
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("Shutting down DLQ handler cleanup scheduler");
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        attemptTracker.clear();
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
