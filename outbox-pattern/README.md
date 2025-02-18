# Outbox Pattern

Reliable event publishing with database + messaging atomicity.

## Why This Exists

The dual-write problem:

```java
// DANGEROUS - What happens if step 2 fails?
orderRepository.save(order);        // Step 1: DB write ✓
kafkaTemplate.send("orders", order); // Step 2: Kafka publish ???
```

If Kafka fails after the DB commit:
- Order is saved but no event is published
- Downstream systems never learn about the order
- Data inconsistency across the system

The outbox pattern **solves this by writing events to the database in the same transaction as the business data**.

## How It Works

```
┌─────────────────────────────────────────────────────┐
│                  SAME TRANSACTION                   │
│                                                     │
│  ┌─────────────┐       ┌─────────────────────────┐  │
│  │   Order     │       │      Outbox Table       │  │
│  │   Table     │       │                         │  │
│  │             │       │  id | aggregate | event │  │
│  │  (business  │       │  -- | --------- | ----- │  │
│  │   data)     │       │  1  | order-123 | {...} │  │
│  └─────────────┘       └─────────────────────────┘  │
│                                                     │
└─────────────────────────────────────────────────────┘
                          │
                          │ Separate process
                          │ (Polling or CDC)
                          ▼
                   ┌──────────────┐
                   │    Kafka     │
                   │   (events)   │
                   └──────────────┘
```

## Database Schema

```sql
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMP,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_outbox_pending ON outbox_events (status, created_at) 
    WHERE status = 'PENDING';
```

## Usage

```java
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxPublisher outboxPublisher;

    @Transactional
    public Order createOrder(CreateOrderCommand cmd) {
        Order order = new Order(cmd);
        orderRepository.save(order);

        // This writes to outbox table in SAME transaction
        outboxPublisher.publish(
            "Order",
            order.getId(),
            "OrderCreated",
            new OrderCreatedEvent(order)
        );

        return order;
    }
}
```

## Publisher Strategies

| Strategy | Pros | Cons |
|----------|------|------|
| **Polling** | Simple, no extra infra | Latency, DB load |
| **CDC (Debezium)** | Real-time, efficient | Complex setup |
| **Transaction Log Tailing** | Fastest | DB-specific |

This implementation uses **polling** — simple and works everywhere.

## What I Learned

1. **Idempotency is mandatory** — duplicate events will happen, consumers must handle them
2. **Keep payloads small** — store IDs, not full objects
3. **Monitor outbox table size** — growing = publisher problem
4. **Clean up published events** — archive or delete after TTL
