package com.aporkolab.demo.notification;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Notification service endpoints and DLQ monitoring")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(summary = "List sent notifications")
    public ResponseEntity<List<NotificationService.Notification>> listNotifications() {
        return ResponseEntity.ok(notificationService.getSentNotifications());
    }

    @GetMapping("/dlq")
    @Operation(summary = "List DLQ messages", description = "Returns messages that failed processing")
    public ResponseEntity<List<NotificationService.DlqMessage>> listDlqMessages() {
        return ResponseEntity.ok(notificationService.getDlqMessages());
    }

    @GetMapping("/stats")
    @Operation(summary = "Get notification stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "sentCount", notificationService.getSentNotifications().size(),
                "dlqCount", notificationService.getDlqMessages().size(),
                "failureRate", notificationService.getFailureRate()
        ));
    }

    @PostMapping("/failure-rate")
    @Operation(summary = "Set failure rate for demo")
    public ResponseEntity<Map<String, Object>> setFailureRate(@RequestBody FailureRateRequest request) {
        notificationService.setFailureRate(request.rate());
        return ResponseEntity.ok(Map.of(
                "failureRate", notificationService.getFailureRate()
        ));
    }

    @DeleteMapping
    @Operation(summary = "Clear all notifications and DLQ messages")
    public ResponseEntity<Void> clearNotifications() {
        notificationService.clearNotifications();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "notification-service"
        ));
    }

    public record FailureRateRequest(double rate) {}
}
