# ADR-004: Dead Letter Queue Failure Categorization

## Status
Accepted

## Date
2024-01-18

## Context

When messages fail processing, they are routed to a Dead Letter Queue (DLQ). However, not all failures are equal:

| Failure Type | Example | Action Needed |
|--------------|---------|---------------|
| Transient | Network timeout | Auto-retry later |
| Permanent | Invalid JSON | Manual fix |
| Business | Order already cancelled | Review logic |
| Resource | Database full | Fix infrastructure |

Without categorization, operators cannot efficiently triage DLQ messages.

## Decision

We will implement **failure categorization** in the DLQ handler with distinct failure types and corresponding metadata.

### Failure Types

```java
public enum FailureType {
    // Temporary issues - auto-retry candidates
    TRANSIENT,
    
    // Permanent issues - require manual intervention
    PERMANENT,
    
    // Business rule violations
    VALIDATION_ERROR,
    
    // Infrastructure issues
    INFRASTRUCTURE_ERROR,
    
    // Retry limit exceeded
    MAX_RETRIES_EXCEEDED,
    
    // Unknown/unclassified errors
    UNKNOWN
}
```

### DLQ Message Structure

```json
{
  "originalTopic": "orders",
  "originalPartition": 3,
  "originalOffset": 12847,
  "originalKey": "order-123",
  "originalValue": "{\"orderId\":\"order-123\",...}",
  "originalTimestamp": "2024-01-18T10:30:00Z",
  
  "failureType": "VALIDATION_ERROR",
  "failureReason": "Order amount exceeds maximum allowed: 50000 > 10000",
  "failureTimestamp": "2024-01-18T10:30:05Z",
  "retryCount": 3,
  "exceptionClass": "com.example.ValidationException",
  "stackTrace": "..."
}
```

### Classification Logic

```java
public FailureType classify(Exception e) {
    return switch (e) {
        case JsonParseException _ -> PERMANENT;
        case ValidationException _ -> VALIDATION_ERROR;
        case SocketTimeoutException _ -> TRANSIENT;
        case SQLException sql when sql.getSQLState().startsWith("08") -> INFRASTRUCTURE_ERROR;
        case RetryExhaustedException _ -> MAX_RETRIES_EXCEEDED;
        default -> UNKNOWN;
    };
}
```

## Alternatives Considered

### 1. Single DLQ for All Failures
- **Pros**: Simple setup
- **Cons**: No triage capability, operators overwhelmed
- **Rejected**: Does not meet operational needs

### 2. Separate Topics per Failure Type
- **Pros**: Easy filtering, separate retention
- **Cons**: Topic explosion, complex routing
- **Rejected**: Over-engineering for most cases

### 3. HTTP Status Code Mapping
- **Pros**: Familiar model
- **Cons**: Not all failures map to HTTP semantics
- **Rejected**: Poor fit for messaging context

## Consequences

### Positive
- **Efficient triage**: Operators can filter by failure type
- **Automation potential**: Auto-retry TRANSIENT failures
- **Metrics clarity**: Failure type breakdown in dashboards
- **Root cause analysis**: Full context preserved

### Negative
- **Classification overhead**: Need to maintain mapping logic
- **False positives**: Some exceptions hard to classify
- **Larger messages**: Additional metadata increases size

### Operational Workflow

```
┌─────────────────────────────────────────────────────────────────┐
│                         DLQ Dashboard                           │
├─────────────────────────────────────────────────────────────────┤
│  TRANSIENT (45)     │ [Auto-Retry] [View] [Dismiss]            │
│  ├─ Network timeout │                                          │
│  └─ Service unavailable                                        │
├─────────────────────────────────────────────────────────────────┤
│  VALIDATION_ERROR (12) │ [View] [Create Ticket]                │
│  ├─ Invalid order amount                                       │
│  └─ Missing required field                                     │
├─────────────────────────────────────────────────────────────────┤
│  PERMANENT (3)      │ [View] [Archive]                         │
│  └─ Malformed JSON                                             │
└─────────────────────────────────────────────────────────────────┘
```

## Kafka Headers

In addition to the message body, we set Kafka headers for filtering without deserialization:

```java
record.headers()
    .add("dlq-reason", failureType.name().getBytes())
    .add("dlq-timestamp", timestamp.toString().getBytes())
    .add("dlq-retry-count", String.valueOf(retryCount).getBytes())
    .add("dlq-original-topic", originalTopic.getBytes());
```

## References

- [Kafka Error Handling Patterns](https://www.confluent.io/blog/error-handling-patterns-in-kafka/)
- [AWS SQS Dead Letter Queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)
- [Enterprise Integration Patterns: Dead Letter Channel](https://www.enterpriseintegrationpatterns.com/patterns/messaging/DeadLetterChannel.html)
