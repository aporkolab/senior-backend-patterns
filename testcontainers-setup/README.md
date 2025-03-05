# Testcontainers Setup

Real databases in tests. No mocks, no H2, no surprises in production.

## Why This Exists

H2 and mocks lie to you:

| Issue | H2 Behavior | PostgreSQL Behavior |
|-------|-------------|---------------------|
| `ON CONFLICT` | ❌ Not supported | ✅ Works |
| JSON operators | ❌ Different syntax | ✅ Native support |
| Locking | ⚠️ Different semantics | ✅ Real behavior |
| Indexes | ⚠️ No query plan impact | ✅ Real performance |

Testcontainers runs **real databases in Docker** during tests. Your tests hit the same database engine as production.

## Setup

### Base Test Configuration

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

### Writing Tests

```java
class OrderRepositoryTest extends IntegrationTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldFindOrdersByStatus() {
        // Given
        Order pending = new Order("ORD-1", OrderStatus.PENDING);
        Order completed = new Order("ORD-2", OrderStatus.COMPLETED);
        orderRepository.saveAll(List.of(pending, completed));

        // When
        List<Order> result = orderRepository.findByStatus(OrderStatus.PENDING);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("ORD-1");
    }
}
```

## Multi-Container Setup (DB + Kafka)

```java
@Testcontainers
public abstract class FullIntegrationTestBase {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.4.0")
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Database
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
```

## Performance Tips

| Technique | Impact | When to Use |
|-----------|--------|-------------|
| Reuse containers | 10x faster | Always (default in this setup) |
| Parallel tests | 2-5x faster | When tests are isolated |
| Lazy init | Faster startup | When not all tests need DB |
| Ryuk disabled | Slight speedup | CI environments |

## Configuration

```yaml
# application-test.yml
spring:
  datasource:
    # Overridden by Testcontainers
    url: jdbc:tc:postgresql:15-alpine:///testdb
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
  flyway:
    enabled: true
    locations: classpath:db/migration
```

## What I Learned

1. **Container reuse is essential** — without it, tests are painfully slow
2. **Use the same DB version as production** — minor version differences matter
3. **Test migrations too** — run Flyway/Liquibase in test profile
4. **Parallelize carefully** — shared state causes flaky tests
