package com.aporkolab.demo.notification;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.*;

import com.aporkolab.patterns.messaging.dlq.DeadLetterQueueHandler;
import com.aporkolab.patterns.messaging.dlq.FailureType;
import com.aporkolab.patterns.messaging.dlq.RetryableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Notification Service - demonstrates Dead Letter Queue pattern.
 * 
 * Features:
 * - Consumes OrderCreated events from Kafka
 * - Sends notifications (email, SMS, push)
 * - Routes failed messages to DLQ with full context
 * - Supports manual DLQ reprocessing
 */
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}

@RestController
@RequestMapping("/notifications")
class NotificationController {

    private final NotificationService notificationService;

    NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/dlq/count")
    public ResponseEntity<Map<String, Long>> getDlqCount() {
        return ResponseEntity.ok(Map.of(
                "pendingRetry", notificationService.getPendingDlqCount()
        ));
    }

    @PostMapping("/dlq/retry")
    public ResponseEntity<Map<String, String>> retryDlq() {
        notificationService.retryDlqMessages();
        return ResponseEntity.ok(Map.of("status", "retry initiated"));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "notification-service"));
    }
}

@org.springframework.stereotype.Service
class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final DeadLetterQueueHandler dlqHandler;
    private final ObjectMapper objectMapper;
    private long dlqCount = 0;

    NotificationService(DeadLetterQueueHandler dlqHandler, ObjectMapper objectMapper) {
        this.dlqHandler = dlqHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * Consume OrderCreated events and send notifications.
     * Failed messages are routed to DLQ with retry logic.
     */
    @KafkaListener(topics = "order.events", groupId = "notification-service")
    public void handleOrderEvent(ConsumerRecord<String, String> record) {
        log.info("Received event: topic={}, key={}", record.topic(), record.key());

        try {
            // Parse and validate event
            JsonNode event = parseEvent(record.value());
            String eventType = determineEventType(event);

            // Process based on event type
            switch (eventType) {
                case "OrderCreated" -> handleOrderCreated(event);
                case "OrderPaid" -> handleOrderPaid(event);
                default -> log.debug("Ignoring event type: {}", eventType);
            }

            // Clear retry attempts on success
            dlqHandler.clearAttempts(record);

        } catch (InvalidEventException e) {
            // Non-retryable: bad message format
            log.error("Invalid event format, sending to DLQ: {}", e.getMessage());
            dlqHandler.sendToDlq(record, e, FailureType.DESERIALIZATION_ERROR);
            dlqCount++;

        } catch (NotificationDeliveryException e) {
            // Retryable: temporary delivery failure
            log.warn("Notification delivery failed, will retry: {}", e.getMessage());
            dlqHandler.handleFailure(record, e, 3);

        } catch (Exception e) {
            // Unknown error
            log.error("Unexpected error processing event: {}", e.getMessage());
            dlqHandler.handleFailure(record, e, 3);
        }
    }

    private JsonNode parseEvent(String value) throws InvalidEventException {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new InvalidEventException("Failed to parse JSON: " + e.getMessage());
        }
    }

    private String determineEventType(JsonNode event) {
        // In real implementation, would check event metadata
        if (event.has("paymentId")) {
            return "OrderPaid";
        }
        return "OrderCreated";
    }

    private void handleOrderCreated(JsonNode event) throws NotificationDeliveryException {
        String orderId = event.path("orderId").asText();
        String customerId = event.path("customerId").asText();

        log.info("Processing OrderCreated notification: orderId={}, customerId={}",
                orderId, customerId);

        // Simulate notification sending
        sendNotification(customerId, "Order Confirmation",
                String.format("Your order %s has been received!", orderId));
    }

    private void handleOrderPaid(JsonNode event) throws NotificationDeliveryException {
        String orderId = event.path("orderId").asText();
        String paymentId = event.path("paymentId").asText();

        log.info("Processing OrderPaid notification: orderId={}, paymentId={}",
                orderId, paymentId);

        sendNotification("customer", "Payment Confirmed",
                String.format("Payment %s for order %s confirmed!", paymentId, orderId));
    }

    private void sendNotification(String recipient, String subject, String body)
            throws NotificationDeliveryException {
        // Simulate notification sending with occasional failures
        if (Math.random() < 0.1) { // 10% failure rate for demo
            throw new NotificationDeliveryException("SMTP connection failed");
        }

        log.info("Notification sent: recipient={}, subject={}", recipient, subject);
    }

    public long getPendingDlqCount() {
        return dlqCount;
    }

    public void retryDlqMessages() {
        log.info("Initiating DLQ retry process");
        // In real implementation, would consume from DLQ topic and reprocess
    }
}

// Custom exceptions for proper DLQ categorization
class InvalidEventException extends RuntimeException {
    InvalidEventException(String message) {
        super(message);
    }
}

class NotificationDeliveryException extends RuntimeException {
    NotificationDeliveryException(String message) {
        super(message);
    }
}
