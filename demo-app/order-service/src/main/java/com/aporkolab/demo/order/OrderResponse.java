package com.aporkolab.demo.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String customerId,
        String productId,
        Integer quantity,
        BigDecimal amount,
        String status,
        String paymentId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getProductId(),
                order.getQuantity(),
                order.getAmount(),
                order.getStatus().name(),
                order.getPaymentId(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
