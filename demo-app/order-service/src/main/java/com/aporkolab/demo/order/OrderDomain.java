package com.aporkolab.demo.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreakerOpenException;
import com.aporkolab.patterns.outbox.OutboxPublisher;

import jakarta.persistence.*;

/**
 * Order entity with status tracking.
 */
@Entity
@Table(name = "orders")
class Order {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {}

    public Order(String customerId, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.amount = amount;
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markPaid(String paymentId) {
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.updatedAt = Instant.now();
    }

    public void markPaymentFailed() {
        this.status = OrderStatus.PAYMENT_FAILED;
        this.updatedAt = Instant.now();
    }

    public void markPaymentPending() {
        this.status = OrderStatus.PAYMENT_PENDING;
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public OrderStatus getStatus() { return status; }
    public String getPaymentId() { return paymentId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

enum OrderStatus {
    PENDING,
    PAYMENT_PENDING,
    PAID,
    PAYMENT_FAILED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}

/**
 * Events published to Kafka via Outbox Pattern.
 */
record OrderCreatedEvent(
        String orderId,
        String customerId,
        BigDecimal amount,
        Instant createdAt
) {}

record OrderPaidEvent(
        String orderId,
        String paymentId,
        Instant paidAt
) {}

@Repository
interface OrderRepository extends JpaRepository<Order, UUID> {}

/**
 * Order Service - orchestrates order creation with:
 * - Transactional Outbox Pattern for reliable event publishing
 * - Circuit Breaker for resilient payment calls
 */
@Service
class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OutboxPublisher outboxPublisher;
    private final PaymentClient paymentClient;
    private final CircuitBreaker paymentCircuitBreaker;

    OrderService(OrderRepository orderRepository, OutboxPublisher outboxPublisher, PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.outboxPublisher = outboxPublisher;
        this.paymentClient = paymentClient;

        // Configure Circuit Breaker for payment calls
        this.paymentCircuitBreaker = CircuitBreaker.builder()
                .name("payment-service")
                .failureThreshold(3)
                .successThreshold(2)
                .openDurationMs(30000) // 30 seconds
                .build();

        // Log state changes
        paymentCircuitBreaker.onStateChange((from, to) ->
                log.warn("Payment Circuit Breaker: {} -> {}", from, to));
    }

    /**
     * Create order with transactional outbox pattern.
     * Order + Event are written in the same transaction.
     */
    @Transactional
    public Order createOrder(String customerId, BigDecimal amount) {
        // 1. Create and save order
        Order order = new Order(customerId, amount);
        orderRepository.save(order);

        log.info("Order created: id={}, customer={}, amount={}",
                order.getId(), customerId, amount);

        // 2. Publish event via Outbox (same transaction!)
        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getId().toString(),
                customerId,
                amount,
                order.getCreatedAt()
        );
        outboxPublisher.publish("Order", order.getId().toString(), "OrderCreated", event);

        // 3. Attempt payment (with Circuit Breaker)
        processPayment(order);

        return order;
    }

    /**
     * Process payment with Circuit Breaker protection.
     */
    private void processPayment(Order order) {
        try {
            String paymentId = paymentCircuitBreaker.executeWithFallback(
                    () -> paymentClient.processPayment(order.getId(), order.getAmount()),
                    () -> {
                        log.warn("Payment service unavailable, order {} queued for retry", order.getId());
                        return null; // Fallback: mark as pending, retry later
                    }
            );

            if (paymentId != null) {
                order.markPaid(paymentId);
                outboxPublisher.publish("Order", order.getId().toString(), "OrderPaid",
                        new OrderPaidEvent(order.getId().toString(), paymentId, Instant.now()));
                log.info("Order {} paid successfully, paymentId={}", order.getId(), paymentId);
            } else {
                order.markPaymentPending();
            }

        } catch (Exception e) {
            log.error("Payment failed for order {}: {}", order.getId(), e.getMessage());
            order.markPaymentFailed();
        }
    }

    public Optional<Order> findById(UUID id) {
        return orderRepository.findById(id);
    }

    public String getCircuitBreakerStatus() {
        return String.format("state=%s, failures=%d",
                paymentCircuitBreaker.getState(),
                paymentCircuitBreaker.getFailureCount());
    }
}

/**
 * Payment Service client with resilient HTTP.
 */
@Service
class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final com.aporkolab.patterns.resilience.http.ResilientHttpClient httpClient;

    PaymentClient() {
        this.httpClient = com.aporkolab.patterns.resilience.http.ResilientHttpClient.builder()
                .baseUrl("http://localhost:8082")
                .maxRetries(2)
                .initialBackoffMs(100)
                .maxBackoffMs(1000)
                .connectionTimeoutMs(5000)
                .build();
    }

    public String processPayment(UUID orderId, BigDecimal amount) {
        String requestBody = String.format(
                "{\"orderId\":\"%s\",\"amount\":%s}",
                orderId, amount
        );

        log.info("Calling Payment Service for order {}", orderId);

        var response = httpClient.post("/payments/process", requestBody);

        if (response.statusCode() == 200 || response.statusCode() == 201) {
            // Parse payment ID from response (simplified)
            String body = response.body();
            // In real code, use proper JSON parsing
            return "PAY-" + orderId.toString().substring(0, 8);
        }

        throw new RuntimeException("Payment failed with status: " + response.statusCode());
    }
}
