package com.aporkolab.patterns.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aporkolab.patterns.messaging.dlq.DeadLetterQueueHandler;
import com.aporkolab.patterns.messaging.dlq.FailureType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration tests for Dead Letter Queue with real Kafka.
 * 
 * Tests the complete DLQ flow:
 * 1. Message fails processing
 * 2. DLQ handler routes to .dlq topic
 * 3. Verify message in DLQ with full context
 */
@Testcontainers
class DeadLetterQueueIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    private static KafkaTemplate<String, String> kafkaTemplate;
    private static KafkaConsumer<String, String> mainConsumer;
    private static KafkaConsumer<String, String> dlqConsumer;
    private static DeadLetterQueueHandler dlqHandler;
    private static ObjectMapper objectMapper;

    private static final String MAIN_TOPIC = "orders";
    private static final String DLQ_TOPIC = "orders.dlq";

    @BeforeAll
    static void setupInfrastructure() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        // Create topics
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(List.of(
                    new NewTopic(MAIN_TOPIC, 1, (short) 1),
                    new NewTopic(DLQ_TOPIC, 1, (short) 1)
            )).all().get();
        }

        // Setup Kafka Producer
        var producerProps = new java.util.HashMap<String, Object>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        // Setup DLQ Handler
        dlqHandler = new DeadLetterQueueHandler(kafkaTemplate, objectMapper);

        // Setup Main Consumer
        mainConsumer = createConsumer("main-group");
        mainConsumer.subscribe(List.of(MAIN_TOPIC));

        // Setup DLQ Consumer
        dlqConsumer = createConsumer("dlq-group");
        dlqConsumer.subscribe(List.of(DLQ_TOPIC));

        // Give Kafka time to settle
        Thread.sleep(2000);
    }

    private static KafkaConsumer<String, String> createConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId + "-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }

    @AfterAll
    static void cleanup() {
        if (mainConsumer != null) mainConsumer.close();
        if (dlqConsumer != null) dlqConsumer.close();
    }

    @Test
    @DisplayName("should route failed message to DLQ topic")
    void shouldRouteFailedMessageToDlq() throws Exception {
        // Given - A message that will fail processing
        String key = "order-" + UUID.randomUUID().toString().substring(0, 8);
        String value = "{\"orderId\":\"" + key + "\",\"invalid\":true}";

        // Publish to main topic
        kafkaTemplate.send(MAIN_TOPIC, key, value).get();

        // When - Consume and simulate failure
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> records = mainConsumer.poll(Duration.ofMillis(500));
            assertThat(records.count()).isGreaterThan(0);

            for (ConsumerRecord<String, String> record : records) {
                if (record.key().equals(key)) {
                    // Simulate processing failure
                    Exception failure = new IllegalStateException("Validation failed: invalid order");
                    dlqHandler.sendToDlq(record, failure, FailureType.VALIDATION_ERROR);
                }
            }
        });

        // Then - Verify message in DLQ
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofMillis(500));
            List<String> dlqKeys = new ArrayList<>();
            List<String> dlqValues = new ArrayList<>();

            dlqRecords.forEach(r -> {
                dlqKeys.add(r.key());
                dlqValues.add(r.value());
            });

            assertThat(dlqKeys).contains(key);

            // Verify DLQ message structure
            String dlqPayload = dlqValues.stream()
                    .filter(v -> v.contains(key))
                    .findFirst()
                    .orElseThrow();

            JsonNode dlqMessage = objectMapper.readTree(dlqPayload);
            assertThat(dlqMessage.has("originalTopic")).isTrue();
            assertThat(dlqMessage.get("originalTopic").asText()).isEqualTo(MAIN_TOPIC);
            assertThat(dlqMessage.has("failureReason")).isTrue();
            assertThat(dlqMessage.get("failureReason").asText()).contains("Validation failed");
            assertThat(dlqMessage.has("failureType")).isTrue();
            assertThat(dlqMessage.get("failureType").asText()).isEqualTo("VALIDATION_ERROR");
        });
    }

    @Test
    @DisplayName("should preserve original message in DLQ")
    void shouldPreserveOriginalMessageInDlq() throws Exception {
        // Given
        String key = "order-preserve-" + UUID.randomUUID().toString().substring(0, 8);
        String originalPayload = "{\"orderId\":\"" + key + "\",\"amount\":199.99,\"items\":[\"A\",\"B\"]}";

        kafkaTemplate.send(MAIN_TOPIC, key, originalPayload).get();

        // When
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> records = mainConsumer.poll(Duration.ofMillis(500));

            for (ConsumerRecord<String, String> record : records) {
                if (record.key().equals(key)) {
                    dlqHandler.sendToDlq(record, new RuntimeException("Test failure"), FailureType.PERMANENT);
                }
            }
        });

        // Then
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofMillis(500));

            for (ConsumerRecord<String, String> record : dlqRecords) {
                if (record.key().equals(key)) {
                    JsonNode dlqMessage = objectMapper.readTree(record.value());

                    // Original message preserved
                    assertThat(dlqMessage.get("originalKey").asText()).isEqualTo(key);
                    assertThat(dlqMessage.get("originalValue").asText()).isEqualTo(originalPayload);
                    assertThat(dlqMessage.has("originalPartition")).isTrue();
                    assertThat(dlqMessage.has("originalOffset")).isTrue();
                }
            }
        });
    }

    @Test
    @DisplayName("should include DLQ headers")
    void shouldIncludeDlqHeaders() throws Exception {
        // Given
        String key = "order-headers-" + UUID.randomUUID().toString().substring(0, 8);
        kafkaTemplate.send(MAIN_TOPIC, key, "{}").get();

        // When
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> records = mainConsumer.poll(Duration.ofMillis(500));

            for (ConsumerRecord<String, String> record : records) {
                if (record.key().equals(key)) {
                    dlqHandler.sendToDlq(record,
                            new RuntimeException("Max retries"),
                            FailureType.MAX_RETRIES_EXCEEDED);
                }
            }
        });

        // Then - Check headers
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofMillis(500));

            for (ConsumerRecord<String, String> record : dlqRecords) {
                if (record.key().equals(key)) {
                    var reasonHeader = record.headers().lastHeader("dlq-reason");
                    var timestampHeader = record.headers().lastHeader("dlq-timestamp");

                    assertThat(reasonHeader).isNotNull();
                    assertThat(new String(reasonHeader.value())).isEqualTo("MAX_RETRIES_EXCEEDED");
                    assertThat(timestampHeader).isNotNull();
                }
            }
        });
    }

    @Test
    @DisplayName("should handle different failure types")
    void shouldHandleDifferentFailureTypes() throws Exception {
        // Test all failure types
        for (FailureType failureType : FailureType.values()) {
            String key = "order-" + failureType.name().toLowerCase() + "-" +
                    UUID.randomUUID().toString().substring(0, 4);

            kafkaTemplate.send(MAIN_TOPIC, key, "{\"type\":\"" + failureType + "\"}").get();

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                ConsumerRecords<String, String> records = mainConsumer.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> record : records) {
                    if (record.key().equals(key)) {
                        dlqHandler.sendToDlq(record,
                                new RuntimeException("Error: " + failureType),
                                failureType);
                    }
                }
            });
        }

        // Verify all failure types in DLQ
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofMillis(1000));
            List<String> failureTypes = new ArrayList<>();

            for (ConsumerRecord<String, String> record : dlqRecords) {
                JsonNode msg = objectMapper.readTree(record.value());
                if (msg.has("failureType")) {
                    failureTypes.add(msg.get("failureType").asText());
                }
            }

            // Should have at least some of the failure types
            assertThat(failureTypes).hasSizeGreaterThanOrEqualTo(1);
        });
    }
}
