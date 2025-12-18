package com.aporkolab.demo.payment;

import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String PAYMENT_EVENTS_TOPIC = "payment-events";

    private final CircuitBreaker paymentGatewayBreaker;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Payment> payments = new ConcurrentHashMap<>();
    
    // Simulated failure rate (for demo)
    private final Random random = new Random();
    private volatile double failureRate = 0.1; // 10% failure rate

    public PaymentService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.paymentGatewayBreaker = CircuitBreaker.builder()
                .name("payment-gateway")
                .failureThreshold(3)
                .successThreshold(2)
                .openDurationMs(10000) // 10 seconds
                .build();

        // Log state changes
        paymentGatewayBreaker.onStateChange((from, to) -> 
            log.warn("Circuit Breaker state changed: {} -> {}", from, to)
        );
    }

    @KafkaListener(topics = "order-events", groupId = "payment-service")
    public void handleOrderEvent(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String eventType = event.get("eventType").asText();

            if ("OrderCreated".equals(eventType)) {
                processPayment(event);
            }
        } catch (Exception e) {
            log.error("Failed to process order event", e);
        }
    }

    private void processPayment(JsonNode event) {
        String orderId = event.get("aggregateId").asText();
        JsonNode payload = event.get("payload");
        
        String customerId = payload.get("customerId").asText();
        BigDecimal amount = new BigDecimal(payload.get("amount").asText());

        log.info("Processing payment for order: {}, amount: {}", orderId, amount);

        try {
            // Use Circuit Breaker to call payment gateway
            PaymentResult result = paymentGatewayBreaker.executeWithFallback(
                    () -> callPaymentGateway(orderId, customerId, amount),
                    () -> {
                        log.warn("Payment gateway circuit is OPEN, using fallback");
                        return new PaymentResult(null, false, "Payment service temporarily unavailable");
                    }
            );

            // Store payment
            Payment payment = new Payment(
                    result.paymentId(),
                    orderId,
                    customerId,
                    amount,
                    result.success() ? PaymentStatus.COMPLETED : PaymentStatus.FAILED,
                    result.message()
            );
            payments.put(orderId, payment);

            // Publish payment result
            publishPaymentResult(payment);

        } catch (Exception e) {
            log.error("Payment processing failed for order: {}", orderId, e);
            Payment failedPayment = new Payment(
                    null, orderId, customerId, amount, 
                    PaymentStatus.FAILED, e.getMessage()
            );
            payments.put(orderId, failedPayment);
            publishPaymentResult(failedPayment);
        }
    }

    private PaymentResult callPaymentGateway(String orderId, String customerId, BigDecimal amount) 
            throws PaymentGatewayException {
        
        // Simulate payment gateway call with configurable failure rate
        simulateLatency();
        
        if (random.nextDouble() < failureRate) {
            throw new PaymentGatewayException("Payment gateway timeout");
        }

        // Simulate successful payment
        String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("Payment successful: {} for order: {}", paymentId, orderId);
        
        return new PaymentResult(paymentId, true, "Payment processed successfully");
    }

    private void simulateLatency() {
        try {
            Thread.sleep(50 + random.nextInt(100)); // 50-150ms latency
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void publishPaymentResult(Payment payment) {
        try {
            String eventType = payment.status() == PaymentStatus.COMPLETED 
                    ? "PaymentCompleted" 
                    : "PaymentFailed";
            
            String eventJson = String.format("""
                    {
                        "eventId": "%s",
                        "eventType": "%s",
                        "orderId": "%s",
                        "paymentId": "%s",
                        "customerId": "%s",
                        "amount": %s,
                        "status": "%s",
                        "message": "%s",
                        "timestamp": "%s"
                    }
                    """,
                    UUID.randomUUID(),
                    eventType,
                    payment.orderId(),
                    payment.paymentId(),
                    payment.customerId(),
                    payment.amount(),
                    payment.status(),
                    payment.message(),
                    java.time.Instant.now()
            );

            kafkaTemplate.send(PAYMENT_EVENTS_TOPIC, payment.orderId(), eventJson);
            log.info("Published {} event for order: {}", eventType, payment.orderId());
            
        } catch (Exception e) {
            log.error("Failed to publish payment event", e);
        }
    }

    // API for demo/testing
    public Payment getPayment(String orderId) {
        return payments.get(orderId);
    }

    public CircuitBreaker.State getCircuitState() {
        return paymentGatewayBreaker.getState();
    }

    public void setFailureRate(double rate) {
        this.failureRate = Math.max(0, Math.min(1, rate));
        log.info("Failure rate set to: {}%", (int)(failureRate * 100));
    }

    public double getFailureRate() {
        return failureRate;
    }

    // Records
    public record Payment(
            String paymentId,
            String orderId,
            String customerId,
            BigDecimal amount,
            PaymentStatus status,
            String message
    ) {}

    public record PaymentResult(String paymentId, boolean success, String message) {}

    public enum PaymentStatus {
        PENDING, COMPLETED, FAILED
    }

    public static class PaymentGatewayException extends Exception {
        public PaymentGatewayException(String message) {
            super(message);
        }
    }
}
