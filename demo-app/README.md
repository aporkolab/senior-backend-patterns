# Demo Application

This demo shows all patterns working together in a realistic e-commerce scenario.

## Architecture

```
┌─────────────────┐    HTTP     ┌─────────────────┐
│                 │ ──────────▶ │                 │
│  Order Service  │             │ Payment Service │
│                 │ ◀────────── │                 │
└────────┬────────┘   Circuit   └─────────────────┘
         │            Breaker
         │
    Outbox Pattern
         │
         ▼
┌─────────────────┐
│                 │
│      Kafka      │
│                 │
└────────┬────────┘
         │
         │  DLQ Handler
         ▼
┌─────────────────────┐
│                     │
│ Notification Service │
│                     │
└─────────────────────┘
```

## Patterns Demonstrated

| Service | Patterns Used |
|---------|--------------|
| Order Service | Circuit Breaker, Outbox Pattern, Exception Framework |
| Payment Service | Resilient HTTP Client, Async Pipeline |
| Notification Service | Dead Letter Queue, Async Pipeline |

## Running the Demo

### Prerequisites
- Docker & Docker Compose
- Java 21+
- Maven 3.9+

### Start Infrastructure
```bash
docker-compose up -d
```

This starts:
- PostgreSQL (port 5432)
- Apache Kafka (port 9092)
- Kafka UI (port 8080)

### Run Services
```bash
# Terminal 1 - Order Service
cd order-service && mvn spring-boot:run

# Terminal 2 - Payment Service  
cd payment-service && mvn spring-boot:run

# Terminal 3 - Notification Service
cd notification-service && mvn spring-boot:run
```

### Test the Flow

1. **Create an order** (triggers payment via Circuit Breaker):
```bash
curl -X POST http://localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "cust-123", "amount": 99.99}'
```

2. **Observe the flow**:
   - Order Service creates order → writes to Outbox
   - Outbox Processor publishes OrderCreated event to Kafka
   - Order Service calls Payment Service (with Circuit Breaker)
   - Notification Service consumes event
   - If notification fails → DLQ handles it

3. **Simulate failures** to see resilience patterns:
```bash
# Kill Payment Service → Circuit Breaker opens
# Restart → Circuit Breaker goes HALF_OPEN → CLOSED

# Send malformed message → DLQ captures it
```

## Service Endpoints

### Order Service (port 8081)
- `POST /orders` - Create order
- `GET /orders/{id}` - Get order
- `GET /actuator/health` - Health check

### Payment Service (port 8082)
- `POST /payments/process` - Process payment
- `GET /actuator/health` - Health check

### Notification Service (port 8083)
- `GET /notifications/dlq/count` - DLQ message count
- `POST /notifications/dlq/retry` - Retry DLQ messages
- `GET /actuator/health` - Health check

## Monitoring

- **Circuit Breaker State**: Check Order Service logs
- **Outbox Queue**: Query `outbox_events` table
- **DLQ Messages**: Check `*.dlq` Kafka topics via Kafka UI
