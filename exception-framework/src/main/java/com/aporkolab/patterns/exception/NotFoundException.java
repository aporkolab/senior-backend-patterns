package com.aporkolab.patterns.exception;

import org.springframework.http.HttpStatus;

/**
 * Resource not found errors.
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String resourceType, String resourceId) {
        super(
            resourceType.toUpperCase() + "_NOT_FOUND",
            String.format("%s not found: %s", resourceType, resourceId),
            HttpStatus.NOT_FOUND
        );
        with("resourceType", resourceType);
        with("resourceId", resourceId);
    }

    public static NotFoundException order(String orderId) {
        return new NotFoundException("Order", orderId);
    }

    public static NotFoundException user(String userId) {
        return new NotFoundException("User", userId);
    }

    public static NotFoundException payment(String paymentId) {
        return new NotFoundException("Payment", paymentId);
    }
}
