package com.aporkolab.demo.order;

import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(
        @NotNull(message = "Status is required")
        Order.OrderStatus status,
        
        String paymentId,
        
        String reason
) {}
