# Circuit Breaker

Fail-fast pattern to protect systems from cascading failures.

## Why This Exists

When a downstream service fails, retrying blindly makes things worse:
- Your threads block waiting for timeouts
- The failing service gets hammered with requests
- Your service becomes unresponsive
- Cascading failure spreads through the system

A circuit breaker **detects failure patterns and stops calling the failing service**, giving it time to recover.

## States

```
     ┌─────────────────────────────────────┐
     │                                     │
     ▼                                     │
┌─────────┐  failure threshold   ┌────────┴───┐
│ CLOSED  │ ──────────────────▶  │    OPEN    │
│ (normal)│                      │ (fail-fast)│
└────┬────┘                      └─────┬──────┘
     │                                 │
     │     success                     │ timeout
     │                                 │
     │         ┌──────────────┐        │
     └─────────│  HALF-OPEN   │◀───────┘
               │  (testing)   │
               └──────────────┘
```

| State | Behavior |
|-------|----------|
| **CLOSED** | Normal operation. Calls pass through. Failures are counted. |
| **OPEN** | Fail-fast. All calls rejected immediately without attempting. |
| **HALF-OPEN** | Testing. Limited calls allowed to check if service recovered. |

## Usage

```java
CircuitBreaker breaker = CircuitBreaker.builder()
    .name("payment-service")
    .failureThreshold(5)           // Open after 5 failures
    .successThreshold(3)           // Close after 3 successes in half-open
    .openDurationMs(30000)         // Stay open for 30 seconds
    .build();

// Wrap your call
try {
    String result = breaker.execute(() -> paymentService.charge(amount));
} catch (CircuitBreakerOpenException e) {
    // Circuit is open - use fallback
    return fallbackResponse();
}

// Or with fallback built-in
String result = breaker.executeWithFallback(
    () -> paymentService.charge(amount),
    () -> "Payment pending - will retry"
);
```

## Configuration Guidelines

| Scenario | failureThreshold | openDuration | successThreshold |
|----------|-----------------|--------------|------------------|
| Critical service (payments) | 3 | 60s | 5 |
| High-traffic service | 10 | 30s | 3 |
| Non-critical service | 5 | 15s | 2 |

## Events

```java
breaker.onStateChange((oldState, newState) -> {
    log.warn("Circuit breaker {} changed: {} -> {}", 
        breaker.getName(), oldState, newState);
    metrics.recordCircuitBreakerTransition(breaker.getName(), newState);
});
```

## What I Learned

1. **Monitor state transitions** — a circuit opening is a symptom, not the disease
2. **Set realistic thresholds** — too sensitive = false positives, too lenient = no protection
3. **Always have a fallback** — open circuit should return *something* useful
4. **Share state across instances** — in distributed systems, consider Redis-backed state
