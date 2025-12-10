package com.aporkolab.patterns.testing;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests requiring PostgreSQL.
 * 
 * Features:
 * - Real PostgreSQL in Docker (same version as production)
 * - Container reuse across tests (fast)
 * - Automatic Spring datasource configuration
 * - Clean state per test class
 * 
 * Usage:
 * Simply extend this class in your test:
 * 
 * class MyRepositoryTest extends PostgresIntegrationTestBase {
 *     @Autowired MyRepository repository;
 *     
 *     @Test
 *     void shouldDoSomething() { ... }
 * }
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class PostgresIntegrationTestBase {

    /**
     * Shared PostgreSQL container.
     * Reused across all tests in the same JVM for speed.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true); // Enable container reuse

    /**
     * Configure Spring datasource to use the Testcontainers PostgreSQL.
     */
    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // JPA settings for tests
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "true");
        registry.add("spring.jpa.properties.hibernate.format_sql", () -> "true");
    }
}
