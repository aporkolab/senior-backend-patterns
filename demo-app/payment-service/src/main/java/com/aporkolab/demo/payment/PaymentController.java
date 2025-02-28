package com.aporkolab.demo.payment;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment service endpoints and circuit breaker controls")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get payment by order ID")
    public ResponseEntity<PaymentService.Payment> getPayment(@PathVariable String orderId) {
        PaymentService.Payment payment = paymentService.getPayment(orderId);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(payment);
    }

    @GetMapping("/circuit-breaker/state")
    @Operation(summary = "Get circuit breaker state", description = "Returns current state of payment gateway circuit breaker")
    public ResponseEntity<CircuitBreakerStatus> getCircuitBreakerState() {
        return ResponseEntity.ok(new CircuitBreakerStatus(
                paymentService.getCircuitState().name(),
                paymentService.getFailureRate()
        ));
    }

    @PostMapping("/circuit-breaker/failure-rate")
    @Operation(summary = "Set failure rate for demo", description = "Adjust simulated failure rate (0.0 - 1.0)")
    public ResponseEntity<Map<String, Object>> setFailureRate(@RequestBody FailureRateRequest request) {
        paymentService.setFailureRate(request.rate());
        return ResponseEntity.ok(Map.of(
                "failureRate", paymentService.getFailureRate(),
                "circuitState", paymentService.getCircuitState().name()
        ));
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "payment-service",
                "circuitBreaker", paymentService.getCircuitState().name()
        ));
    }

    public record CircuitBreakerStatus(String state, double failureRate) {}
    public record FailureRateRequest(double rate) {}
}
