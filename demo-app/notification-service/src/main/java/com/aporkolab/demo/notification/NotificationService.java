package com.aporkolab.demo.notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String DLQ_TOPIC = "notification-events.dlq";
    private static final int MAX_RETRIES = 3;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final List<Notification> sentNotifications = Collections.synchronizedList(new ArrayList<>());
    private final List<DlqMessage> dlqMessages = Collections.synchronizedList(new ArrayList<>());

    // Simulated failure rate for demo
    private final Random random = new Random();
    private volatile double failureRate = 0.1;

    public NotificationService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment-events", groupId = "notification-service")
    public void handlePaymentEvent(ConsumerRecord<String, String> record) {
        String orderId = record.key();
        log.info("Received payment event for order: {}", orderId);

        try {
            JsonNode event = objectMapper.readTree(record.value());
            String eventType = event.get("eventType").asText();

            if ("PaymentCompleted".equals(eventType)) {
                sendPaymentConfirmation(event);
            } else if ("PaymentFailed".equals(eventType)) {
                sendPaymentFailureNotification(event);
            }

        } catch (ValidationException e) {
            // Permanent failure - send to DLQ immediately
            sendToDlq(record, e, FailureType.VALIDATION_ERROR);
        } catch (TransientException e) {
            // Transient failure - could retry, but for demo we send to DLQ
            sendToDlq(record, e, FailureType.TRANSIENT);
        } catch (Exception e) {
            log.error("Unexpected error processing payment event", e);
            sendToDlq(record, e, FailureType.UNKNOWN);
        }
    }

    @KafkaListener(topics = "order-events", groupId = "notification-service")
    public void handleOrderEvent(ConsumerRecord<String, String> record) {
        String orderId = record.key();
        log.info("Received order event for order: {}", orderId);

        try {
            JsonNode event = objectMapper.readTree(record.value());
            String eventType = event.get("eventType").asText();

            if ("OrderCreated".equals(eventType)) {
                sendOrderConfirmation(event);
            } else if ("OrderStatusChanged".equals(eventType)) {
                sendOrderStatusUpdate(event);
            }

        } catch (Exception e) {
            log.error("Error processing order event", e);
            sendToDlq(record, e, FailureType.UNKNOWN);
        }
    }

    private void sendOrderConfirmation(JsonNode event) throws TransientException {
        simulateFailure();
        
        JsonNode payload = event.get("payload");
        String customerId = payload.get("customerId").asText();
        String orderId = payload.get("orderId").asText();

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                customerId,
                "Order Confirmation",
                String.format("Your order %s has been received and is being processed.", orderId),
                NotificationType.EMAIL,
                Instant.now()
        );

        sendNotification(notification);
    }

    private void sendOrderStatusUpdate(JsonNode event) throws TransientException {
        simulateFailure();
        
        JsonNode payload = event.get("payload");
        String orderId = payload.get("orderId").asText();
        String newStatus = payload.get("newStatus").asText();

        // Would need customerId from somewhere - simplified for demo
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                "customer",
                "Order Status Update",
                String.format("Your order %s status changed to: %s", orderId, newStatus),
                NotificationType.EMAIL,
                Instant.now()
        );

        sendNotification(notification);
    }

    private void sendPaymentConfirmation(JsonNode event) throws TransientException, ValidationException {
        String customerId = event.get("customerId").asText();
        String orderId = event.get("orderId").asText();
        String paymentId = event.get("paymentId").asText();
        String amount = event.get("amount").asText();

        // Validate
        if (customerId == null || customerId.isBlank()) {
            throw new ValidationException("Customer ID is required");
        }

        simulateFailure();

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                customerId,
                "Payment Confirmation",
                String.format("Payment %s for order %s ($%s) was successful.", paymentId, orderId, amount),
                NotificationType.EMAIL,
                Instant.now()
        );

        sendNotification(notification);
    }

    private void sendPaymentFailureNotification(JsonNode event) throws TransientException {
        String customerId = event.get("customerId").asText();
        String orderId = event.get("orderId").asText();
        String message = event.has("message") ? event.get("message").asText() : "Unknown error";

        simulateFailure();

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                customerId,
                "Payment Failed",
                String.format("Payment for order %s failed: %s. Please try again.", orderId, message),
                NotificationType.EMAIL,
                Instant.now()
        );

        sendNotification(notification);
    }

    private void sendNotification(Notification notification) {
        // Simulate sending (email, SMS, push, etc.)
        log.info("Sending {} notification to {}: {}", 
                notification.type(), notification.customerId(), notification.subject());
        
        sentNotifications.add(notification);
        
        // In real system, this would call email service, SMS gateway, etc.
        log.info("Notification sent successfully: {}", notification.id());
    }

    private void simulateFailure() throws TransientException {
        if (random.nextDouble() < failureRate) {
            throw new TransientException("Notification service temporarily unavailable");
        }
    }

    private void sendToDlq(ConsumerRecord<String, String> record, Exception e, FailureType type) {
        log.warn("Sending message to DLQ: topic={}, key={}, type={}", 
                record.topic(), record.key(), type);

        DlqMessage dlqMessage = new DlqMessage(
                UUID.randomUUID().toString(),
                record.topic(),
                record.key(),
                record.value(),
                type,
                e.getMessage(),
                e.getClass().getName(),
                Instant.now()
        );

        dlqMessages.add(dlqMessage);

        try {
            String dlqPayload = objectMapper.writeValueAsString(dlqMessage);
            kafkaTemplate.send(DLQ_TOPIC, record.key(), dlqPayload);
        } catch (Exception ex) {
            log.error("Failed to send to DLQ", ex);
        }
    }

    // API methods for demo
    public List<Notification> getSentNotifications() {
        return new ArrayList<>(sentNotifications);
    }

    public List<DlqMessage> getDlqMessages() {
        return new ArrayList<>(dlqMessages);
    }

    public void setFailureRate(double rate) {
        this.failureRate = Math.max(0, Math.min(1, rate));
        log.info("Notification failure rate set to: {}%", (int)(failureRate * 100));
    }

    public double getFailureRate() {
        return failureRate;
    }

    public void clearNotifications() {
        sentNotifications.clear();
        dlqMessages.clear();
    }

    // Records and enums
    public record Notification(
            String id,
            String customerId,
            String subject,
            String body,
            NotificationType type,
            Instant sentAt
    ) {}

    public record DlqMessage(
            String id,
            String originalTopic,
            String key,
            String value,
            FailureType failureType,
            String errorMessage,
            String exceptionClass,
            Instant timestamp
    ) {}

    public enum NotificationType {
        EMAIL, SMS, PUSH
    }

    public enum FailureType {
        TRANSIENT,
        VALIDATION_ERROR,
        INFRASTRUCTURE_ERROR,
        MAX_RETRIES_EXCEEDED,
        UNKNOWN
    }

    // Custom exceptions
    public static class TransientException extends Exception {
        public TransientException(String message) { super(message); }
    }

    public static class ValidationException extends Exception {
        public ValidationException(String message) { super(message); }
    }
}
