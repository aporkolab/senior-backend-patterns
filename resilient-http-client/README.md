# Resilient HTTP Client

HTTP client with retry, exponential backoff, and timeout handling.

## Why This Exists

Networks fail. Services go down. Latency spikes happen. A naive HTTP client treats every failure as final. A resilient one **expects failure and handles it gracefully**.

## Key Features

- **Exponential backoff**: 100ms → 200ms → 400ms → 800ms
- **Jitter**: Randomized delay to prevent thundering herd
- **Configurable retries**: Default 3, max 10
- **Timeout handling**: Connection + read timeouts
- **Selective retry**: Only retry on transient failures (5xx, timeout, connection reset)

## Usage

```java
ResilientHttpClient client = ResilientHttpClient.builder()
    .baseUrl("https://api.example.com")
    .maxRetries(3)
    .initialBackoffMs(100)
    .maxBackoffMs(5000)
    .connectionTimeoutMs(3000)
    .readTimeoutMs(10000)
    .build();

// Synchronous call with automatic retry
HttpResponse<String> response = client.get("/users/123");

// With custom headers
HttpResponse<String> response = client.get("/users/123", 
    Map.of("Authorization", "Bearer token"));

// POST with body
HttpResponse<String> response = client.post("/users", userJson);
```

## When to Retry

| Error Type | Retry? | Reason |
|------------|--------|--------|
| 5xx Server Error | ✅ Yes | Server might recover |
| Connection Timeout | ✅ Yes | Network glitch |
| Read Timeout | ✅ Yes | Slow response |
| 4xx Client Error | ❌ No | Our fault, won't change |
| 401/403 | ❌ No | Auth issue, retry won't help |

## Configuration

```java
// Conservative (for critical paths)
.maxRetries(5)
.initialBackoffMs(200)
.maxBackoffMs(10000)

// Aggressive (for non-critical, latency-sensitive)
.maxRetries(2)
.initialBackoffMs(50)
.maxBackoffMs(500)
```

## What I Learned

1. **Always add jitter** — without it, all retrying clients hit the server at the same time
2. **Log every retry** — silent retries hide problems until they cascade
3. **Set timeouts explicitly** — default timeouts are usually too long for production
