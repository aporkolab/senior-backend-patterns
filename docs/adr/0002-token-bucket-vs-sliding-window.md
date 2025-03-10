# ADR-002: Rate Limiter Algorithm Selection

## Status
Accepted

## Date
2024-01-16

## Context

We need to provide rate limiting capabilities to protect services from overload. Different use cases require different characteristics:

| Use Case | Requirements |
|----------|--------------|
| API Gateway | Smooth rate limiting, burst tolerance |
| Login endpoint | Strict limits, no bursts |
| Background jobs | Simple counting, low memory |

We need to support multiple algorithms to address these varied requirements.

## Decision

We will implement **three rate limiting algorithms** and allow users to choose based on their needs:

### 1. Token Bucket (Default)
Best for: APIs with legitimate burst traffic

```
Bucket Capacity: 100 tokens
Refill Rate: 10 tokens/second

Time 0s:  [████████████████████] 100 tokens
Request burst (50 requests)
Time 0s:  [██████████          ] 50 tokens
Time 1s:  [████████████        ] 60 tokens (refilled)
Time 2s:  [██████████████      ] 70 tokens
```

### 2. Sliding Window
Best for: Precise rate limiting without boundary issues

```
Window: 1 minute, Max: 100 requests

|----|----|----|----|----|----|
  15   20   25   10   15   15  = 100 (at limit)
      └─────── Sliding ───────┘
```

### 3. Fixed Window
Best for: Simple use cases, lowest memory

```
Window: 1 minute, Max: 100 requests

|  Window 1  |  Window 2  |
|    100     |     0      |  <- Resets at boundary
```

### API Design

```java
// Fluent builder for each algorithm
RateLimiter tokenBucket = RateLimiter.tokenBucket()
    .capacity(100)
    .refillRate(10)
    .refillPeriod(Duration.ofSeconds(1))
    .build();

RateLimiter slidingWindow = RateLimiter.slidingWindow()
    .maxRequests(100)
    .windowSize(Duration.ofMinutes(1))
    .build();

// Unified interface
boolean allowed = limiter.tryAcquire("user-123");
```

## Alternatives Considered

### 1. Leaky Bucket
- **Pros**: Smoothest output rate
- **Cons**: Cannot handle bursts, complex to implement correctly
- **Rejected**: Token Bucket provides similar benefits with burst support

### 2. Sliding Window Log
- **Pros**: Most precise
- **Cons**: O(n) memory per key, expensive cleanup
- **Rejected**: Sliding Window Counter approximation is sufficient

### 3. External Rate Limiter (Redis)
- **Pros**: Distributed, battle-tested
- **Cons**: External dependency, network latency
- **Deferred**: Will add Redis-backed implementation in v2

## Consequences

### Positive
- **Flexibility**: Users choose algorithm based on needs
- **No external deps**: In-memory implementation
- **Consistent API**: Same interface for all algorithms
- **Thread-safe**: Lock-free implementations

### Negative
- **Memory per key**: Each key maintains state
- **No distribution**: Single-node only (v1)
- **Clock dependency**: Sliding window sensitive to clock skew

### Trade-offs by Algorithm

| Algorithm | Memory | Precision | Burst Support | Complexity |
|-----------|--------|-----------|---------------|------------|
| Token Bucket | Low | Medium | Yes | Medium |
| Sliding Window | Medium | High | No | High |
| Fixed Window | Lowest | Low | Boundary burst | Low |

## Migration Path

For users needing distributed rate limiting:

1. **v1 (current)**: In-memory, single-node
2. **v2 (planned)**: Redis-backed for distribution
3. **v3 (planned)**: Cluster-aware with consistent hashing

## References

- [Stripe Rate Limiters](https://stripe.com/blog/rate-limiters)
- [Google Cloud Rate Limiting](https://cloud.google.com/architecture/rate-limiting-strategies-techniques)
- [Kong Rate Limiting Plugin](https://docs.konghq.com/hub/kong-inc/rate-limiting/)
