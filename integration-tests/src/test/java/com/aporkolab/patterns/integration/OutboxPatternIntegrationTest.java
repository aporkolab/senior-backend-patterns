package com.aporkolab.patterns.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for Outbox Pattern with real PostgreSQL and Kafka.
 * 
 * Tests the complete flow:
 * 1. Write event to outbox table (Postgres)
 * 2. Process outbox and publish to Kafka
 * 3. Verify message received in Kafka
 */
@Testcontainers
class OutboxPatternIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    private static JdbcTemplate jdbcTemplate;
    private static KafkaTemplate<String, String> kafkaTemplate;
    private static KafkaConsumer<String, String> consumer;

    @BeforeAll
    static void setupInfrastructure() {
        // Setup Postgres
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        // Create outbox table
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS outbox_events (
                id UUID PRIMARY KEY,
                aggregate_type VARCHAR(100) NOT NULL,
                aggregate_id VARCHAR(100) NOT NULL,
                event_type VARCHAR(100) NOT NULL,
                payload TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                published_at TIMESTAMP,
                status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
            )
        """);

        // Setup Kafka Producer
        var producerProps = new java.util.HashMap<String, Object>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        // Setup Kafka Consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(consumerProps);
    }

    @AfterAll
    static void cleanup() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @BeforeEach
    void clearOutbox() {
        jdbcTemplate.execute("DELETE FROM outbox_events");
    }

    @Test
    @DisplayName("should persist event to outbox table")
    void shouldPersistEventToOutbox() {
        // Given
        String eventId = UUID.randomUUID().toString();
        String payload = "{\"orderId\":\"order-123\",\"amount\":99.99}";

        // When
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, created_at)
            VALUES (?::uuid, ?, ?, ?, ?, 'PENDING', NOW())
            """,
                eventId, "Order", "order-123", "OrderCreated", payload);

        // Then
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("should use SKIP LOCKED for concurrent processing")
    void shouldUseSkipLockedForConcurrentProcessing() {
        // Given - Insert multiple events
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, created_at)
                VALUES (?::uuid, ?, ?, ?, ?, 'PENDING', NOW())
                """,
                    UUID.randomUUID().toString(), "Order", "order-" + i, "OrderCreated", "{}");
        }

        // When - Simulate SKIP LOCKED query (what OutboxProcessor uses)
        List<String> lockedIds = jdbcTemplate.queryForList("""
            SELECT id::text FROM outbox_events 
            WHERE status = 'PENDING' 
            ORDER BY created_at ASC 
            LIMIT 3 
            FOR UPDATE SKIP LOCKED
            """, String.class);

        // Then
        assertThat(lockedIds).hasSize(3);
    }

    @Test
    @DisplayName("should publish event to Kafka and mark as published")
    void shouldPublishToKafkaAndMarkPublished() {
        // Given
        String eventId = UUID.randomUUID().toString();
        String aggregateId = "order-" + UUID.randomUUID().toString().substring(0, 8);
        String payload = "{\"orderId\":\"" + aggregateId + "\",\"amount\":150.00}";
        String topic = "order.events";

        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, created_at)
            VALUES (?::uuid, ?, ?, ?, ?, 'PENDING', NOW())
            """,
                eventId, "Order", aggregateId, "OrderCreated", payload);

        // Subscribe to topic
        consumer.subscribe(List.of(topic));

        // When - Simulate OutboxProcessor behavior
        var pendingEvents = jdbcTemplate.queryForList(
                "SELECT id, aggregate_id, payload FROM outbox_events WHERE status = 'PENDING'");

        for (var event : pendingEvents) {
            String key = (String) event.get("aggregate_id");
            String value = (String) event.get("payload");

            // Publish to Kafka
            kafkaTemplate.send(topic, key, value).join();

            // Mark as published
            jdbcTemplate.update(
                    "UPDATE outbox_events SET status = 'PUBLISHED', published_at = NOW() WHERE id = ?::uuid",
                    event.get("id").toString());
        }

        // Then - Verify message received
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            List<String> receivedKeys = new ArrayList<>();
            records.forEach(r -> receivedKeys.add(r.key()));
            assertThat(receivedKeys).contains(aggregateId);
        });

        // Verify status updated
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?::uuid",
                String.class, eventId);
        assertThat(status).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("should handle batch processing correctly")
    void shouldHandleBatchProcessing() {
        // Given - Insert 10 events
        for (int i = 0; i < 10; i++) {
            jdbcTemplate.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, created_at)
                VALUES (?::uuid, ?, ?, ?, ?, 'PENDING', NOW())
                """,
                    UUID.randomUUID().toString(), "Order", "order-" + i, "OrderCreated",
                    "{\"index\":" + i + "}");
        }

        // When - Process in batches of 3
        int batchSize = 3;
        int processed = 0;

        while (true) {
            List<String> batch = jdbcTemplate.queryForList("""
                SELECT id::text FROM outbox_events 
                WHERE status = 'PENDING' 
                ORDER BY created_at ASC 
                LIMIT ?
                """, String.class, batchSize);

            if (batch.isEmpty()) break;

            for (String id : batch) {
                jdbcTemplate.update(
                        "UPDATE outbox_events SET status = 'PUBLISHED', published_at = NOW() WHERE id = ?::uuid",
                        id);
                processed++;
            }
        }

        // Then
        assertThat(processed).isEqualTo(10);

        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'",
                Integer.class);
        assertThat(pendingCount).isZero();
    }

    @Test
    @DisplayName("should cleanup old published events")
    void shouldCleanupOldPublishedEvents() {
        // Given - Insert old published event
        String oldEventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, created_at, published_at)
            VALUES (?::uuid, ?, ?, ?, ?, 'PUBLISHED', NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days')
            """,
                oldEventId, "Order", "old-order", "OrderCreated", "{}");

        // Insert recent published event
        String recentEventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, created_at, published_at)
            VALUES (?::uuid, ?, ?, ?, ?, 'PUBLISHED', NOW(), NOW())
            """,
                recentEventId, "Order", "recent-order", "OrderCreated", "{}");

        // When - Cleanup events older than 7 days
        int deleted = jdbcTemplate.update("""
            DELETE FROM outbox_events 
            WHERE status = 'PUBLISHED' 
            AND published_at < NOW() - INTERVAL '7 days'
            """);

        // Then
        assertThat(deleted).isEqualTo(1);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events",
                Integer.class);
        assertThat(remaining).isEqualTo(1);
    }
}
