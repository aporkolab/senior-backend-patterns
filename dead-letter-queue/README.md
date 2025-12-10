# Dead Letter Queue Pattern

Handle poison messages without losing data or blocking processing.

## Why This Exists

Some messages can't be processed:
- Invalid data format
- Missing required fields
- Business logic rejection
- Transient errors that persist

Without a DLQ, these messages either:
1. **Block the queue** — retried forever, nothing else processes
2. **Get lost** — acknowledged without processing, data disappears

A Dead Letter Queue **captures failed messages for later investigation and reprocessing**.

## Architecture

```
                    ┌─────────────┐
                    │   Main      │
   Producer ──────▶ │   Topic     │
                    └──────┬──────┘
                           │
                           ▼
                    ┌─────────────┐
                    │  Consumer   │
                    │  (process)  │
                    └──────┬──────┘
                           │
            ┌──────────────┼──────────────┐
            │              │              │
            ▼              ▼              ▼
         Success      Retryable      Permanent
         (commit)      Failure        Failure
                          │              │
                          ▼              ▼
                    ┌──────────┐   ┌──────────┐
                    │  Retry   │   │   DLQ    │
                    │  Topic   │   │  Topic   │
                    └──────────┘   └──────────┘
```

## When to DLQ

| Failure Type | Action | Example |
|--------------|--------|---------|
| Transient | Retry with backoff | DB connection timeout |
| Permanent | Send to DLQ | Invalid JSON |
| Business rejection | Send to DLQ | Duplicate order |
| Unknown | Retry N times, then DLQ | Unexpected exception |

## Usage

```java
@Component
public class OrderConsumer {

    private final DeadLetterQueueHandler dlqHandler;
    private final OrderService orderService;

    @KafkaListener(topics = "orders")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            Order order = parseOrder(record.value());
            orderService.process(order);
        } catch (ValidationException e) {
            // Permanent failure - DLQ immediately
            dlqHandler.sendToDlq(record, e, FailureType.PERMANENT);
        } catch (Exception e) {
            // Unknown failure - retry or DLQ based on attempt count
            dlqHandler.handleFailure(record, e);
        }
    }
}
```

## DLQ Message Structure

```json
{
  "originalTopic": "orders",
  "originalPartition": 2,
  "originalOffset": 12345,
  "originalKey": "order-123",
  "originalValue": "{...}",
  "originalTimestamp": "2024-01-15T10:30:00Z",
  "failureReason": "ValidationException: Invalid currency code",
  "failureType": "PERMANENT",
  "attemptCount": 1,
  "failedAt": "2024-01-15T10:30:05Z",
  "consumerGroup": "order-processor",
  "hostname": "app-pod-xyz"
}
```

## Reprocessing Strategy

```java
// Manual reprocessing from DLQ
@Scheduled(cron = "0 0 * * * *") // Every hour
public void reprocessDlq() {
    dlqHandler.reprocessMessages(
        message -> {
            // Fix known issues
            if (message.getFailureReason().contains("currency")) {
                return fixCurrencyCode(message);
            }
            return message;
        },
        3 // Max reprocess attempts
    );
}
```

## What I Learned

1. **Include all context in DLQ messages** — you'll need it at 3 AM
2. **Monitor DLQ size** — growing DLQ = systemic problem
3. **Set retention on DLQ topic** — don't let it grow forever
4. **Separate DLQ topics per consumer** — easier to debug and reprocess
