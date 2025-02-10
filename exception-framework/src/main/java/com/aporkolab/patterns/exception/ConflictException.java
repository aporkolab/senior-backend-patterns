package com.aporkolab.patterns.exception;

import org.springframework.http.HttpStatus;

/**
 * Conflict errors - duplicate resources, concurrent modifications.
 */
public class ConflictException extends BusinessException {

    public ConflictException(String resourceType, String resourceId, String reason) {
        super(
            resourceType.toUpperCase() + "_CONFLICT",
            String.format("Conflict for %s '%s': %s", resourceType, resourceId, reason),
            HttpStatus.CONFLICT
        );
        with("resourceType", resourceType);
        with("resourceId", resourceId);
        with("reason", reason);
    }

    public static ConflictException duplicate(String resourceType, String resourceId) {
        return new ConflictException(resourceType, resourceId, "already exists");
    }

    public static ConflictException concurrentModification(String resourceType, String resourceId) {
        return new ConflictException(resourceType, resourceId, "modified by another process");
    }
}
