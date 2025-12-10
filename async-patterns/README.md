# Async Patterns

Non-blocking pipelines with CompletableFuture and Virtual Threads.

## Why This Exists

Blocking I/O wastes threads:

```java
// BLOCKING - Thread sits idle during network call
String user = userService.getUser(id);        // 100ms waiting
String orders = orderService.getOrders(id);   // 100ms waiting
// Total: 200ms, 1 thread blocked entire time
```

Async pipelines **free threads during I/O**:

```java
// NON-BLOCKING - Thread released during waits
CompletableFuture<String> userFuture = userService.getUserAsync(id);
CompletableFuture<String> ordersFuture = orderService.getOrdersAsync(id);
// Total: 100ms (parallel), thread available for other work
```

## Patterns Included

### 1. Parallel Independent Calls

```java
CompletableFuture<UserProfile> profile = asyncPipeline.parallel(
    () -> userService.getUser(id),
    () -> orderService.getOrders(id),
    () -> paymentService.getBalance(id)
).thenApply(results -> buildProfile(results));
```

### 2. Sequential with Dependencies

```java
CompletableFuture<Order> order = asyncPipeline
    .validateUser(userId)
    .thenCompose(user -> checkInventory(user, items))
    .thenCompose(inventory -> createOrder(inventory))
    .thenCompose(order -> processPayment(order));
```

### 3. Fan-out / Fan-in

```java
List<CompletableFuture<Price>> priceFutures = vendors.stream()
    .map(vendor -> getPriceAsync(vendor, product))
    .toList();

CompletableFuture<Price> bestPrice = asyncPipeline
    .allOf(priceFutures)
    .thenApply(prices -> prices.stream().min(comparing(Price::amount)));
```

### 4. Timeout Handling

```java
CompletableFuture<Data> withTimeout = asyncPipeline
    .withTimeout(fetchData(), Duration.ofSeconds(5))
    .exceptionally(ex -> fallbackData());
```

## Java 21 Virtual Threads

With virtual threads, you can write blocking code that scales:

```java
// This looks blocking but uses virtual threads under the hood
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<Result>> futures = tasks.stream()
        .map(task -> executor.submit(() -> process(task)))
        .toList();
}
```

## Error Handling

| Method | Behavior |
|--------|----------|
| `exceptionally()` | Handle exception, return fallback |
| `handle()` | Handle both success and failure |
| `whenComplete()` | Side effects only, doesn't transform |
| `exceptionallyCompose()` | Async fallback on failure |

## What I Learned

1. **Don't block in async code** — one `.get()` ruins the whole pipeline
2. **Use dedicated executors** — don't starve the common ForkJoinPool
3. **Timeouts are mandatory** — async without timeout = memory leak waiting to happen
4. **Virtual threads change everything** — but understand when to use them
