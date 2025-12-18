package com.aporkolab.demo.order;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.*;

/**
 * Order Service - demonstrates Circuit Breaker + Outbox Pattern.
 * 
 * Flow:
 * 1. Receive order request
 * 2. Save order to DB
 * 3. Write OrderCreated event to Outbox (same transaction)
 * 4. Call Payment Service (protected by Circuit Breaker)
 * 5. Outbox Processor publishes events to Kafka asynchronously
 */
@SpringBootApplication
@EnableScheduling
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

@RestController
@RequestMapping("/orders")
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        Order order = orderService.createOrder(request.customerId(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OrderResponse(
                        order.getId(),
                        order.getCustomerId(),
                        order.getAmount(),
                        order.getStatus().name(),
                        order.getPaymentId()
                ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID id) {
        return orderService.findById(id)
                .map(order -> ResponseEntity.ok(new OrderResponse(
                        order.getId(),
                        order.getCustomerId(),
                        order.getAmount(),
                        order.getStatus().name(),
                        order.getPaymentId()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/circuit-breaker/status")
    public ResponseEntity<String> getCircuitBreakerStatus() {
        return ResponseEntity.ok(orderService.getCircuitBreakerStatus());
    }

    // DTOs
    record CreateOrderRequest(String customerId, BigDecimal amount) {}
    record OrderResponse(UUID id, String customerId, BigDecimal amount, String status, String paymentId) {}
}
