package com.aporkolab.demo.order;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aporkolab.patterns.ratelimiter.RateLimitExceededException;
import com.aporkolab.patterns.ratelimiter.RateLimiter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order management endpoints")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    
    private final OrderService orderService;
    private final RateLimiter rateLimiter;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
        this.rateLimiter = RateLimiter.tokenBucket()
                .name("order-api")
                .capacity(100)
                .refillRate(10)
                .build();
    }

    @PostMapping
    @Operation(summary = "Create a new order", description = "Creates a new order and publishes OrderCreated event via Outbox pattern")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Order created successfully",
                content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestParam(required = false, defaultValue = "anonymous") String clientId) {
        
        checkRateLimit(clientId);
        
        log.info("POST /api/v1/orders - Creating order for customer: {}", request.customerId());
        Order order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID", description = "Retrieves order details by order ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order found"),
        @ApiResponse(responseCode = "404", description = "Order not found")
    })
    public ResponseEntity<OrderResponse> getOrder(
            @Parameter(description = "Order UUID") @PathVariable UUID orderId) {
        
        log.info("GET /api/v1/orders/{}", orderId);
        Order order = orderService.getOrder(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping
    @Operation(summary = "List all orders", description = "Retrieves all orders, optionally filtered by customer")
    public ResponseEntity<List<OrderResponse>> listOrders(
            @Parameter(description = "Filter by customer ID") 
            @RequestParam(required = false) String customerId) {
        
        log.info("GET /api/v1/orders - customerId: {}", customerId);
        
        List<Order> orders = customerId != null 
                ? orderService.getOrdersByCustomer(customerId)
                : orderService.getAllOrders();
        
        return ResponseEntity.ok(orders.stream().map(OrderResponse::from).toList());
    }

    @PutMapping("/{orderId}/status")
    @Operation(summary = "Update order status", description = "Updates the status of an existing order")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status updated"),
        @ApiResponse(responseCode = "404", description = "Order not found"),
        @ApiResponse(responseCode = "400", description = "Invalid status transition")
    })
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable UUID orderId,
            @Valid @RequestBody UpdateStatusRequest request) {
        
        log.info("PUT /api/v1/orders/{}/status - {}", orderId, request.status());
        Order order = orderService.updateOrderStatus(
                orderId, 
                request.status(), 
                request.paymentId(), 
                request.reason()
        );
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Cancel order", description = "Cancels an order if not yet completed")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order cancelled"),
        @ApiResponse(responseCode = "404", description = "Order not found"),
        @ApiResponse(responseCode = "400", description = "Cannot cancel completed order")
    })
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable UUID orderId) {
        log.info("DELETE /api/v1/orders/{}", orderId);
        Order order = orderService.cancelOrder(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Returns service health status")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("UP", "order-service"));
    }

    private void checkRateLimit(String clientId) {
        if (!rateLimiter.tryAcquire(clientId)) {
            throw new RateLimitExceededException(
                    rateLimiter.getName(),
                    clientId,
                    java.time.Duration.ofSeconds(1),
                    rateLimiter.getRemainingPermits(clientId)
            );
        }
    }

    
    public record HealthResponse(String status, String service) {}
}
