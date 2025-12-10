# Exception Framework

Domain-specific errors with clean boundaries.

## Why This Exists

Generic exceptions hide intent:

```java
// BAD - What went wrong? Who knows.
throw new RuntimeException("Something failed");
throw new IllegalStateException("Invalid");

// GOOD - Intent is clear
throw new PaymentDeclinedException(orderId, "Insufficient funds");
throw new OrderNotFoundException(orderId);
```

A domain exception framework provides:
- **Clarity**: Exception name tells you what happened
- **Context**: Structured data for debugging
- **HTTP mapping**: Automatic status code translation
- **Consistency**: Same error handling patterns everywhere

## Architecture

```
DomainException (abstract base)
├── BusinessException (4xx - client errors)
│   ├── ValidationException
│   ├── NotFoundException
│   ├── ConflictException
│   └── UnauthorizedException
│
└── TechnicalException (5xx - server errors)
    ├── IntegrationException
    ├── DatabaseException
    └── InfrastructureException
```

## Usage

### Throwing

```java
public Order getOrder(String orderId) {
    return orderRepository.findById(orderId)
        .orElseThrow(() -> new OrderNotFoundException(orderId));
}

public void processPayment(Payment payment) {
    if (payment.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
        throw new ValidationException("amount", "Must be positive");
    }

    try {
        paymentGateway.charge(payment);
    } catch (GatewayException e) {
        throw new PaymentDeclinedException(payment.getId(), e.getMessage(), e);
    }
}
```

### Global Handler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        return ResponseEntity
            .status(ex.getHttpStatus())
            .body(ErrorResponse.from(ex));
    }

    @ExceptionHandler(TechnicalException.class)
    public ResponseEntity<ErrorResponse> handleTechnical(TechnicalException ex) {
        // Log full details, return generic message to client
        log.error("Technical error: {}", ex.getMessage(), ex);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.generic("An error occurred"));
    }
}
```

### Error Response

```json
{
  "code": "ORDER_NOT_FOUND",
  "message": "Order not found: ORD-123",
  "timestamp": "2024-01-15T10:30:00Z",
  "traceId": "abc-123-def",
  "details": {
    "orderId": "ORD-123"
  }
}
```

## Best Practices

| Do | Don't |
|----|----|
| Create specific exception types | Reuse generic exceptions |
| Include context (IDs, values) | Include sensitive data |
| Map to appropriate HTTP status | Return 500 for everything |
| Log technical details server-side | Expose stack traces to clients |

## What I Learned

1. **Exception names are documentation** — `InsufficientInventoryException` beats `IllegalStateException`
2. **Context is king** — include IDs, field names, expected vs actual values
3. **Separate business from technical** — different handling, different visibility
4. **Don't catch-and-rethrow needlessly** — preserve the stack trace
