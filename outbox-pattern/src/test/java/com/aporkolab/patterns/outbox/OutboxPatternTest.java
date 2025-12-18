package com.aporkolab.patterns.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxPatternTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private OutboxPublisher outboxPublisher;
    private OutboxProcessor outboxProcessor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxPublisher = new OutboxPublisher(outboxRepository, objectMapper);
        outboxProcessor = new OutboxProcessor(outboxRepository, kafkaTemplate);
    }

    @Nested
    @DisplayName("OutboxEvent Entity")
    class OutboxEventEntityTest {

        @Test
        @DisplayName("should create event with PENDING status")
        void shouldCreateWithPendingStatus() {
            OutboxEvent event = new OutboxEvent("Order", "order-123", "OrderCreated", "{\"amount\":100}");

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getPublishedAt()).isNull();
        }

        @Test
        @DisplayName("should generate UUID on creation")
        void shouldGenerateUuid() {
            OutboxEvent event = new OutboxEvent("Order", "order-123", "OrderCreated", "{}");

            assertThat(event.getId()).isNotNull();
        }

        @Test
        @DisplayName("should set creation timestamp")
        void shouldSetCreationTimestamp() {
            Instant before = Instant.now();
            OutboxEvent event = new OutboxEvent("Order", "order-123", "OrderCreated", "{}");
            Instant after = Instant.now();

            assertThat(event.getCreatedAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("should mark as published correctly")
        void shouldMarkAsPublished() {
            OutboxEvent event = new OutboxEvent("Order", "order-123", "OrderCreated", "{}");
            
            event.markPublished();

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(event.getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("should mark as failed correctly")
        void shouldMarkAsFailed() {
            OutboxEvent event = new OutboxEvent("Order", "order-123", "OrderCreated", "{}");
            
            event.markFailed();

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        }

        @Test
        @DisplayName("should generate topic name from aggregate type")
        void shouldGenerateTopicName() {
            OutboxEvent orderEvent = new OutboxEvent("Order", "id", "type", "{}");
            OutboxEvent userAccountEvent = new OutboxEvent("USER_ACCOUNT", "id", "type", "{}");

            assertThat(orderEvent.getTopicName()).isEqualTo("order.events");
            assertThat(userAccountEvent.getTopicName()).isEqualTo("user-account.events");
        }

        @Test
        @DisplayName("should store all properties correctly")
        void shouldStoreAllProperties() {
            OutboxEvent event = new OutboxEvent("Payment", "pay-456", "PaymentProcessed", "{\"status\":\"success\"}");

            assertThat(event.getAggregateType()).isEqualTo("Payment");
            assertThat(event.getAggregateId()).isEqualTo("pay-456");
            assertThat(event.getEventType()).isEqualTo("PaymentProcessed");
            assertThat(event.getPayload()).isEqualTo("{\"status\":\"success\"}");
        }
    }

    @Nested
    @DisplayName("OutboxPublisher")
    class OutboxPublisherTest {

        @Test
        @DisplayName("should save event to repository")
        void shouldSaveEventToRepository() {
            outboxPublisher.publishRaw("Order", "order-1", "OrderCreated", "{\"test\":true}");

            verify(outboxRepository).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("should serialize object payload to JSON")
        void shouldSerializePayloadToJson() {
            TestEvent testEvent = new TestEvent("test-id", 42);
            
            outboxPublisher.publish("Order", "order-1", "OrderCreated", testEvent);

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepository).save(captor.capture());

            assertThat(captor.getValue().getPayload()).contains("\"id\":\"test-id\"");
            assertThat(captor.getValue().getPayload()).contains("\"value\":42");
        }

        @Test
        @DisplayName("should throw OutboxException on serialization failure")
        void shouldThrowOnSerializationFailure() {
            Object unserializable = new Object() {
                // Anonymous class that Jackson can't serialize
                public Object getSelf() { return this; }
            };

            assertThatThrownBy(() -> 
                outboxPublisher.publish("Order", "order-1", "OrderCreated", unserializable))
                .isInstanceOf(OutboxException.class)
                .hasMessageContaining("serialize");
        }

        @Test
        @DisplayName("should allow raw JSON payload")
        void shouldAllowRawJsonPayload() {
            String rawJson = "{\"customField\":\"customValue\"}";
            
            outboxPublisher.publishRaw("Order", "order-1", "OrderCreated", rawJson);

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepository).save(captor.capture());

            assertThat(captor.getValue().getPayload()).isEqualTo(rawJson);
        }
    }

    @Nested
    @DisplayName("OutboxProcessor")
    class OutboxProcessorTest {

        @Test
        @DisplayName("should skip processing when no pending events")
        void shouldSkipWhenNoPendingEvents() {
            when(outboxRepository.findPendingEventsForUpdate(anyInt())).thenReturn(Collections.emptyList());

            outboxProcessor.processOutbox();

            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should process pending events and publish to Kafka")
        void shouldProcessPendingEvents() throws Exception {
            OutboxEvent event = createPendingEvent("Order", "order-123", "OrderCreated");
            when(outboxRepository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event));
            mockKafkaSend();

            outboxProcessor.processOutbox();

            verify(kafkaTemplate).send(eq("order.events"), eq("order-123"), anyString());
        }

        @Test
        @DisplayName("should mark event as published after successful Kafka send")
        void shouldMarkAsPublishedAfterSend() throws Exception {
            OutboxEvent event = createPendingEvent("Order", "order-123", "OrderCreated");
            when(outboxRepository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event));
            mockKafkaSend();

            outboxProcessor.processOutbox();

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            verify(outboxRepository).save(event);
        }

        @Test
        @DisplayName("should mark event as failed on Kafka error")
        void shouldMarkAsFailedOnKafkaError() throws Exception {
            OutboxEvent event = createPendingEvent("Order", "order-123", "OrderCreated");
            when(outboxRepository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event));
            when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));

            outboxProcessor.processOutbox();

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
            verify(outboxRepository).save(event);
        }

        @Test
        @DisplayName("should process multiple events in batch")
        void shouldProcessMultipleEvents() throws Exception {
            OutboxEvent event1 = createPendingEvent("Order", "o1", "OrderCreated");
            OutboxEvent event2 = createPendingEvent("Order", "o2", "OrderCreated");
            OutboxEvent event3 = createPendingEvent("Payment", "p1", "PaymentCompleted");
            
            when(outboxRepository.findPendingEventsForUpdate(anyInt()))
                    .thenReturn(Arrays.asList(event1, event2, event3));
            mockKafkaSend();

            outboxProcessor.processOutbox();

            verify(kafkaTemplate, times(3)).send(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should continue processing after individual failure")
        void shouldContinueAfterIndividualFailure() throws Exception {
            OutboxEvent event1 = createPendingEvent("Order", "o1", "OrderCreated");
            OutboxEvent event2 = createPendingEvent("Order", "o2", "OrderCreated");

            when(outboxRepository.findPendingEventsForUpdate(anyInt()))
                    .thenReturn(Arrays.asList(event1, event2));
            
            // First fails, second succeeds
            when(kafkaTemplate.send(eq("order.events"), eq("o1"), anyString()))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("fail")));
            when(kafkaTemplate.send(eq("order.events"), eq("o2"), anyString()))
                    .thenReturn(mockSuccessfulSend());

            outboxProcessor.processOutbox();

            assertThat(event1.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(event2.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        }

        @Test
        @DisplayName("should return pending count for monitoring")
        void shouldReturnPendingCount() {
            when(outboxRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(42L);

            long count = outboxProcessor.getPendingCount();

            assertThat(count).isEqualTo(42L);
        }

        @Test
        @DisplayName("should return failed count for monitoring")
        void shouldReturnFailedCount() {
            when(outboxRepository.countByStatus(OutboxStatus.FAILED)).thenReturn(5L);

            long count = outboxProcessor.getFailedCount();

            assertThat(count).isEqualTo(5L);
        }

        @Test
        @DisplayName("should retry failed events")
        void shouldRetryFailedEvents() throws Exception {
            OutboxEvent failedEvent = createPendingEvent("Order", "o1", "OrderCreated");
            failedEvent.markFailed();
            
            when(outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.FAILED))
                    .thenReturn(List.of(failedEvent));
            mockKafkaSend();

            outboxProcessor.retryFailedEvents();

            assertThat(failedEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        }
    }

    @Nested
    @DisplayName("OutboxStatus Enum")
    class OutboxStatusTest {

        @Test
        @DisplayName("should have all expected statuses")
        void shouldHaveAllStatuses() {
            assertThat(OutboxStatus.values()).containsExactlyInAnyOrder(
                    OutboxStatus.PENDING,
                    OutboxStatus.PUBLISHED,
                    OutboxStatus.FAILED
            );
        }
    }

    // Helper methods and classes
    private OutboxEvent createPendingEvent(String aggregateType, String aggregateId, String eventType) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, "{\"test\":true}");
    }

    @SuppressWarnings("unchecked")
    private void mockKafkaSend() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(mockSuccessfulSend());
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, String>> mockSuccessfulSend() {
        SendResult<String, String> result = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("test", 0), 0L, 0, 0L, 0, 0
        );
        when(result.getRecordMetadata()).thenReturn(metadata);
        return CompletableFuture.completedFuture(result);
    }

    // Test DTO
    static class TestEvent {
        private String id;
        private int value;

        public TestEvent() {}

        public TestEvent(String id, int value) {
            this.id = id;
            this.value = value;
        }

        public String getId() { return id; }
        public int getValue() { return value; }
    }
}
