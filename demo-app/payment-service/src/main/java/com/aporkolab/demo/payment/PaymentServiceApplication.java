package com.aporkolab.demo.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.aporkolab.patterns.async.AsyncPipeline;

/**
 * Payment Service - demonstrates Resilient HTTP Client + Async Pipeline.
 * 
 * Features:
 * - Async payment processing with timeout handling
 * - Concurrent fraud check + balance check
 * - Graceful degradation
 */
@SpringBootApplication
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}

@RestController
@RequestMapping("/payments")
class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private final PaymentProcessor paymentProcessor;

    PaymentController(PaymentProcessor paymentProcessor) {
        this.paymentProcessor = paymentProcessor;
    }

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processPayment(@RequestBody PaymentRequest request) {
        log.info("Received payment request: orderId={}, amount={}",
                request.orderId(), request.amount());

        try {
            PaymentResult result = paymentProcessor.process(request.orderId(), request.amount());
            return ResponseEntity.ok(new PaymentResponse(
                    result.paymentId(),
                    result.status(),
                    result.processedAt()
            ));
        } catch (Exception e) {
            log.error("Payment processing failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(new PaymentResponse(null, "FAILED", Instant.now()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "payment-service"));
    }

    record PaymentRequest(String orderId, BigDecimal amount) {}
    record PaymentResponse(String paymentId, String status, Instant processedAt) {}
}

@org.springframework.stereotype.Service
class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    private final AsyncPipeline asyncPipeline;

    PaymentProcessor() {
        this.asyncPipeline = new AsyncPipeline();
    }

    /**
     * Process payment with parallel fraud check and balance verification.
     * Uses AsyncPipeline for concurrent execution with timeout.
     */
    public PaymentResult process(String orderId, BigDecimal amount) {
        log.info("Processing payment for order: {}", orderId);

        // Run fraud check and balance check in parallel
        CompletableFuture<Boolean> fraudCheck = asyncPipeline.async(() -> checkFraud(orderId, amount));
        CompletableFuture<Boolean> balanceCheck = asyncPipeline.async(() -> checkBalance(orderId, amount));

        // Wait for both with timeout
        CompletableFuture<java.util.List<Boolean>> checks = asyncPipeline.allOf(
                java.util.List.of(fraudCheck, balanceCheck)
        );

        CompletableFuture<java.util.List<Boolean>> withTimeout = asyncPipeline.withTimeout(
                checks,
                java.time.Duration.ofSeconds(5)
        );

        try {
            java.util.List<Boolean> results = withTimeout.get();
            boolean fraudPassed = results.get(0);
            boolean balancePassed = results.get(1);

            if (!fraudPassed) {
                log.warn("Fraud check failed for order: {}", orderId);
                return new PaymentResult(null, "FRAUD_REJECTED", Instant.now());
            }

            if (!balancePassed) {
                log.warn("Insufficient balance for order: {}", orderId);
                return new PaymentResult(null, "INSUFFICIENT_FUNDS", Instant.now());
            }

            // Both checks passed - process payment
            String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
            log.info("Payment successful: paymentId={}, orderId={}", paymentId, orderId);

            return new PaymentResult(paymentId, "SUCCESS", Instant.now());

        } catch (Exception e) {
            log.error("Payment processing timeout or error: {}", e.getMessage());
            throw new RuntimeException("Payment processing failed", e);
        }
    }

    private boolean checkFraud(String orderId, BigDecimal amount) {
        // Simulate fraud check (would call external service)
        simulateLatency(100);
        log.debug("Fraud check passed for order: {}", orderId);
        return amount.compareTo(new BigDecimal("10000")) < 0; // Reject if > 10k
    }

    private boolean checkBalance(String orderId, BigDecimal amount) {
        // Simulate balance check
        simulateLatency(150);
        log.debug("Balance check passed for order: {}", orderId);
        return true; // Always pass for demo
    }

    private void simulateLatency(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

record PaymentResult(String paymentId, String status, Instant processedAt) {}
