package com.aporkolab.demo.order;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {
    
    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order not found: " + orderId);
        this.orderId = orderId;
    }

    public UUID getOrderId() {
        return orderId;
    }
}
