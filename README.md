# Senior Backend Patterns

Production-tested patterns I use in real-world backend systems. Not tutorials — **working skeletons** ready to copy into projects.

## What's Inside

| Pattern | Purpose | Key Insight |
|---------|---------|-------------|
| [Resilient HTTP Client](resilient-http-client/) | Retry + exponential backoff + timeout | Don't trust the network |
| [Circuit Breaker](circuit-breaker/) | Fail-fast under pressure | Protect downstream services |
| [Dead Letter Queue](dead-letter-queue/) | Handle poison messages | Never lose data silently |
| [Outbox Pattern](outbox-pattern/) | Reliable event publishing | DB + messaging atomicity |
| [Testcontainers Setup](testcontainers-setup/) | Real DB in tests | Don't mock what you can run |
| [Async Patterns](async-patterns/) | CompletableFuture flows | Non-blocking pipelines |
| [Exception Framework](exception-framework/) | Domain-specific errors | Clean error boundaries |

## Philosophy

> "Make it work, make it right, make it fast — in that order."

These patterns prioritize:
- **Clarity** over cleverness
- **Maintainability** over premature optimization
- **Production-readiness** over demo-ware

## Tech Stack

- Java 21 (Virtual Threads where applicable)
- Spring Boot 3.x
- Testcontainers
- PostgreSQL
- Apache Kafka

## How to Use

Each pattern is self-contained. Copy the relevant package into your project and adapt.

```bash
# Run all tests
./mvnw test

# Run specific pattern tests
./mvnw test -Dtest=CircuitBreakerTest
```

## Architecture Decisions

### Why not use Resilience4j directly?

I do — in production. These skeletons show the **underlying mechanics** so you understand what the library does. When Resilience4j breaks (and it will, at 3 AM), you'll know how to debug it.

### Why Testcontainers over H2?

H2 lies. It tells you your query works, then PostgreSQL disagrees in production. Real databases catch real bugs.

### Why custom exceptions?

Generic exceptions hide intent. `PaymentDeclinedException` tells you more than `RuntimeException("payment failed")`.

## License

MIT — use freely, attribute if you're feeling generous.

## Author

[Ádám Porkoláb](https://aporkolab.com) — Senior Fullstack Engineer
