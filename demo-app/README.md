# Demo Application: Order Processing Flow

End-to-end demonstration of all patterns working together in a realistic microservices scenario.

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌──────────────────┐
│   Client    │────▶│   Order     │────▶│   PostgreSQL     │
│  (curl/UI)  │     │   Service   │     │   (Orders +      │
└─────────────┘     │   :8081     │     │    Outbox)       │
                    └──────┬──────┘     └──────────────────┘
                           │
                           │ Kafka: order-events
                           ▼
                    ┌──────────────┐
                    │   Payment    │───────▶ Kafka: payment-events
                    │   Service    │
                    │   :8082      │
                    └──────┬───────┘
                           │ Circuit Breaker
                           │ (External Gateway)
                           ▼
                    ┌──────────────┐
                    │ Notification │───────▶ Kafka: notification-events.dlq
                    │   Service    │         (Dead Letter Queue)
                    │   :8083      │
                    └──────────────┘
```

## Patterns Demonstrated

| Service | Patterns |
|---------|----------|
| **Order Service** | Outbox Pattern, Rate Limiter, Swagger/OpenAPI |
| **Payment Service** | Circuit Breaker, Event-Driven Processing |
| **Notification Service** | DLQ Handling, Failure Classification |
| **All Services** | Correlation ID, Structured Logging, Prometheus Metrics |

## Quick Start

### 1. Start Infrastructure

```bash
cd demo-app
docker-compose up -d postgres kafka zookeeper kafka-ui prometheus grafana
```

### 2. Build Services

```bash
# From project root
mvn clean package -DskipTests -pl demo-app/order-service,demo-app/payment-service,demo-app/notification-service -am
```

### 3. Start Services

```bash
# Terminal 1 - Order Service
java -jar demo-app/order-service/target/order-service-1.0.0.jar

# Terminal 2 - Payment Service  
java -jar demo-app/payment-service/target/payment-service-1.0.0.jar

# Terminal 3 - Notification Service
java -jar demo-app/notification-service/target/notification-service-1.0.0.jar
```

## End-to-End Flow Test

### Step 1: Create an Order

```bash
curl -X POST http://localhost:8081/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-001",
    "productId": "prod-abc",
    "quantity": 2,
    "amount": 99.99
  }'
```

**Expected Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "customerId": "cust-001",
  "productId": "prod-abc",
  "quantity": 2,
  "amount": 99.99,
  "status": "PENDING",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

### Step 2: Observe Event Flow

**Kafka UI:** http://localhost:8090

Check topics:
- `order-events` - OrderCreated event
- `payment-events` - PaymentCompleted/PaymentFailed event
- `notification-events.dlq` - Failed notifications

### Step 3: Check Notifications

```bash
curl http://localhost:8083/api/v1/notifications
```

### Step 4: Trigger Circuit Breaker

```bash
# Set payment gateway failure rate to 100%
curl -X POST http://localhost:8082/api/v1/payments/circuit-breaker/failure-rate \
  -H "Content-Type: application/json" \
  -d '{"rate": 1.0}'

# Create several orders to trigger circuit breaker
for i in {1..5}; do
  curl -X POST http://localhost:8081/api/v1/orders \
    -H "Content-Type: application/json" \
    -d "{\"customerId\": \"test-$i\", \"productId\": \"prod-$i\", \"quantity\": 1, \"amount\": 10.00}"
done

# Check circuit state
curl http://localhost:8082/api/v1/payments/circuit-breaker/state
```

**Expected:** Circuit breaker should open after 3 failures.

### Step 5: Reset and Recover

```bash
# Reset failure rate
curl -X POST http://localhost:8082/api/v1/payments/circuit-breaker/failure-rate \
  -H "Content-Type: application/json" \
  -d '{"rate": 0.0}'

# Wait for circuit to close (~10 seconds)
# Then create new orders - they should succeed
```

## API Documentation (Swagger)

| Service | Swagger UI |
|---------|------------|
| Order Service | http://localhost:8081/swagger-ui.html |
| Payment Service | http://localhost:8082/swagger-ui.html |
| Notification Service | http://localhost:8083/swagger-ui.html |

## Observability

### Prometheus Metrics

http://localhost:9090

**Key Metrics:**
- `circuit_breaker_state` - Current state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
- `circuit_breaker_calls_total` - Call counts by result
- `rate_limiter_rejected_total` - Rate limited requests
- `outbox_events_pending` - Pending outbox events
- `dlq_depth` - Messages in DLQ

### Grafana Dashboard

http://localhost:3000 (admin/admin)

Pre-configured dashboard: **Senior Backend Patterns**

## Service Health

```bash
# All health endpoints
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health  
curl http://localhost:8083/actuator/health
```

## Rate Limiting Demo

```bash
# Rapid-fire requests to trigger rate limiting
for i in {1..20}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8081/api/v1/orders?clientId=test-client \
    -H "Content-Type: application/json" \
    -d '{"customerId": "rate-test", "productId": "p1", "quantity": 1, "amount": 1.00}'
done
```

**Expected:** After ~10 requests, you should see `429 Too Many Requests`.

## DLQ Demo

```bash
# Set notification failure rate high
curl -X POST http://localhost:8083/api/v1/notifications/failure-rate \
  -H "Content-Type: application/json" \
  -d '{"rate": 0.8}'

# Create orders - some notifications will fail
for i in {1..10}; do
  curl -X POST http://localhost:8081/api/v1/orders \
    -H "Content-Type: application/json" \
    -d "{\"customerId\": \"dlq-test-$i\", \"productId\": \"p1\", \"quantity\": 1, \"amount\": 5.00}"
done

# Check DLQ
curl http://localhost:8083/api/v1/notifications/dlq
```

## Cleanup

```bash
docker-compose down -v
```

## Troubleshooting

### Services won't start
- Check PostgreSQL is running: `docker-compose ps`
- Check Kafka is healthy: `docker-compose logs kafka`

### Events not flowing
- Check Kafka topics in Kafka UI
- Check service logs for errors

### Circuit breaker stuck open
- Wait for open duration (10s) to expire
- Check payment service logs
