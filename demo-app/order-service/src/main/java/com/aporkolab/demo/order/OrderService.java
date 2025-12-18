package com.aporkolab.demo.order;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String ORDERS_TOPIC = "orders";
    private static final String ORDER_EVENTS_TOPIC = "order-events";

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OrderService(
            OrderRepository orderRepository,
            OutboxEventRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        log.info("Creating order for customer: {}", request.customerId());

        // Create order
        Order order = new Order();
        order.setCustomerId(request.customerId());
        order.setProductId(request.productId());
        order.setQuantity(request.quantity());
        order.setAmount(request.amount());
        order.setStatus(Order.OrderStatus.PENDING);

        order = orderRepository.save(order);

        // Create outbox event (same transaction)
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setAggregateType("Order");
        outboxEvent.setAggregateId(order.getId().toString());
        outboxEvent.setEventType("OrderCreated");
        outboxEvent.setPayload(toJson(new OrderCreatedEvent(
                order.getId(),
                order.getCustomerId(),
                order.getProductId(),
                order.getQuantity(),
                order.getAmount()
        )));

        outboxRepository.save(outboxEvent);

        log.info("Order created: {} with outbox event", order.getId());
        return order;
    }

    @Transactional
    public Order updateOrderStatus(UUID orderId, Order.OrderStatus status, String paymentId, String failureReason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        Order.OrderStatus oldStatus = order.getStatus();
        order.setStatus(status);
        
        if (paymentId != null) {
            order.setPaymentId(paymentId);
        }
        if (failureReason != null) {
            order.setFailureReason(failureReason);
        }

        order = orderRepository.save(order);

        // Create status change event
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setAggregateType("Order");
        outboxEvent.setAggregateId(order.getId().toString());
        outboxEvent.setEventType("OrderStatusChanged");
        outboxEvent.setPayload(toJson(new OrderStatusChangedEvent(
                order.getId(),
                oldStatus.name(),
                status.name(),
                paymentId,
                failureReason
        )));

        outboxRepository.save(outboxEvent);

        log.info("Order {} status changed: {} -> {}", orderId, oldStatus, status);
        return order;
    }

    public Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public List<Order> getOrdersByCustomer(String customerId) {
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    public Order cancelOrder(UUID orderId) {
        Order order = getOrder(orderId);
        
        if (order.getStatus() == Order.OrderStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel completed order");
        }

        return updateOrderStatus(orderId, Order.OrderStatus.CANCELLED, null, "Cancelled by customer");
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event", e);
        }
    }

    // Event records
    public record OrderCreatedEvent(
            UUID orderId,
            String customerId,
            String productId,
            Integer quantity,
            java.math.BigDecimal amount
    ) {}

    public record OrderStatusChangedEvent(
            UUID orderId,
            String oldStatus,
            String newStatus,
            String paymentId,
            String reason
    ) {}
}
