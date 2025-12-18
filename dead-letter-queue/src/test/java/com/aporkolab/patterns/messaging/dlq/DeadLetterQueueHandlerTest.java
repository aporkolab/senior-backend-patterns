package com.aporkolab.patterns.messaging.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
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
class DeadLetterQueueHandlerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private DeadLetterQueueHandler dlqHandler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // For Java 8 date/time
        dlqHandler = new DeadLetterQueueHandler(kafkaTemplate, objectMapper);
    }

    @Nested
    @DisplayName("Send to DLQ")
    class SendToDlq {

        @Test
        @DisplayName("should send message to DLQ topic with correct suffix")
        void shouldSendToDlqTopic() {
            ConsumerRecord<String, String> record = createRecord("orders", "key1", "{\"orderId\":123}");
            Exception exception = new RuntimeException("Processing failed");

            mockKafkaSend();

            dlqHandler.sendToDlq(record, exception, FailureType.VALIDATION_ERROR);

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());

            assertThat(captor.getValue().topic()).isEqualTo("orders.dlq");
        }

        @Test
        @DisplayName("should preserve original key in DLQ message")
        void shouldPreserveOriginalKey() {
            ConsumerRecord<String, String> record = createRecord("orders", "order-123", "{\"data\":\"test\"}");
            
            mockKafkaSend();

            dlqHandler.sendToDlq(record, new RuntimeException("error"), FailureType.PERMANENT);

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());

            assertThat(captor.getValue().key()).isEqualTo("order-123");
        }

        @Test
        @DisplayName("should include failure reason in DLQ message payload")
        void shouldIncludeFailureReason() throws Exception {
            ConsumerRecord<String, String> record = createRecord("orders", "key", "{\"value\":1}");
            Exception exception = new IllegalStateException("Invalid order state");

            mockKafkaSend();

            dlqHandler.sendToDlq(record, exception, FailureType.VALIDATION_ERROR);

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());

            DlqMessage dlqMessage = objectMapper.readValue(captor.getValue().value(), DlqMessage.class);
            assertThat(dlqMessage.getFailureReason()).contains("IllegalStateException");
            assertThat(dlqMessage.getFailureReason()).contains("Invalid order state");
        }

        @Test
        @DisplayName("should include failure type in DLQ message")
        void shouldIncludeFailureType() throws Exception {
            ConsumerRecord<String, String> record = createRecord("payments", "pay-1", "{}");

            mockKafkaSend();

            dlqHandler.sendToDlq(record, new RuntimeException("error"), FailureType.DESERIALIZATION_ERROR);

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());

            DlqMessage dlqMessage = objectMapper.readValue(captor.getValue().value(), DlqMessage.class);
            assertThat(dlqMessage.getFailureType()).isEqualTo(FailureType.DESERIALIZATION_ERROR);
        }

        @Test
        @DisplayName("should preserve original message metadata")
        void shouldPreserveOriginalMetadata() throws Exception {
            ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    "original-topic", 2, 12345L, "key", "{\"data\":\"value\"}"
            );

            mockKafkaSend();

            dlqHandler.sendToDlq(record, new RuntimeException("error"), FailureType.UNKNOWN);

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());

            DlqMessage dlqMessage = objectMapper.readValue(captor.getValue().value(), DlqMessage.class);
            assertThat(dlqMessage.getOriginalTopic()).isEqualTo("original-topic");
            assertThat(dlqMessage.getOriginalPartition()).isEqualTo(2);
            assertThat(dlqMessage.getOriginalOffset()).isEqualTo(12345L);
            assertThat(dlqMessage.getOriginalValue()).isEqualTo("{\"data\":\"value\"}");
        }

        @Test
        @DisplayName("should add DLQ-specific headers")
        void shouldAddDlqHeaders() {
            ConsumerRecord<String, String> record = createRecord("events", "e1", "{}");

            mockKafkaSend();

            dlqHandler.sendToDlq(record, new RuntimeException("err"), FailureType.MAX_RETRIES_EXCEEDED);

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());

            ProducerRecord<String, String> sent = captor.getValue();
            assertThat(sent.headers().lastHeader("dlq-reason")).isNotNull();
            assertThat(new String(sent.headers().lastHeader("dlq-reason").value()))
                    .isEqualTo("MAX_RETRIES_EXCEEDED");
            assertThat(sent.headers().lastHeader("dlq-timestamp")).isNotNull();
        }

        @Test
        @DisplayName("should include truncated stack trace")
        void shouldIncludeTruncatedStackTrace() throws Exception {
            ConsumerRecord<String, String> record = createRecord("test", "k", "v");
            Exception exception = new RuntimeException("Deep error");

            mockKafkaSend();

            dlqHandler.sendToDlq(record, exception, FailureType.UNKNOWN);

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());

            DlqMessage dlqMessage = objectMapper.readValue(captor.getValue().value(), DlqMessage.class);
            assertThat(dlqMessage.getStackTrace()).isNotEmpty();
            assertThat(dlqMessage.getStackTrace().length()).isLessThanOrEqualTo(2100);
        }
    }

    @Nested
    @DisplayName("Retry Handling")
    class RetryHandling {

        @Test
        @DisplayName("should throw RetryableException when retry limit not exceeded")
        void shouldThrowRetryableWhenUnderLimit() {
            ConsumerRecord<String, String> record = createRecord("orders", "o1", "{}", 0, 100L);
            Exception exception = new RuntimeException("Transient error");

            assertThatThrownBy(() -> dlqHandler.handleFailure(record, exception, 3))
                    .isInstanceOf(RetryableException.class)
                    .hasMessageContaining("Retry attempt 1");
        }

        @Test
        @DisplayName("should send to DLQ after max retries")
        void shouldSendToDlqAfterMaxRetries() {
            ConsumerRecord<String, String> record = createRecord("orders", "o1", "{}", 0, 100L);
            Exception exception = new RuntimeException("Persistent error");

            mockKafkaSend();

            // First two retries
            for (int i = 0; i < 2; i++) {
                try {
                    dlqHandler.handleFailure(record, exception, 3);
                } catch (RetryableException ignored) {}
            }

            // Third attempt should send to DLQ
            dlqHandler.handleFailure(record, exception, 3);

            verify(kafkaTemplate).send(any(ProducerRecord.class));
        }

        @Test
        @DisplayName("should track attempts per message key")
        void shouldTrackAttemptsPerMessageKey() {
            ConsumerRecord<String, String> record1 = createRecord("orders", "k1", "{}", 0, 100L);
            ConsumerRecord<String, String> record2 = createRecord("orders", "k2", "{}", 0, 200L);
            Exception exception = new RuntimeException("error");

            // Fail record1 twice
            for (int i = 0; i < 2; i++) {
                try {
                    dlqHandler.handleFailure(record1, exception, 3);
                } catch (RetryableException ignored) {}
            }

            // record2 should start fresh
            assertThatThrownBy(() -> dlqHandler.handleFailure(record2, exception, 3))
                    .isInstanceOf(RetryableException.class)
                    .hasMessageContaining("Retry attempt 1");
        }

        @Test
        @DisplayName("should clear attempts after success")
        void shouldClearAttemptsAfterSuccess() {
            ConsumerRecord<String, String> record = createRecord("orders", "o1", "{}", 0, 100L);
            Exception exception = new RuntimeException("error");

            // Fail once
            try {
                dlqHandler.handleFailure(record, exception, 3);
            } catch (RetryableException ignored) {}

            // Clear on success
            dlqHandler.clearAttempts(record);

            // Next failure should be attempt 1 again
            assertThatThrownBy(() -> dlqHandler.handleFailure(record, exception, 3))
                    .isInstanceOf(RetryableException.class)
                    .hasMessageContaining("Retry attempt 1");
        }

        @Test
        @DisplayName("should use default max retries when not specified")
        void shouldUseDefaultMaxRetries() {
            ConsumerRecord<String, String> record = createRecord("orders", "o1", "{}", 0, 100L);
            Exception exception = new RuntimeException("error");

            mockKafkaSend();

            // Default is 3 retries
            for (int i = 0; i < 2; i++) {
                try {
                    dlqHandler.handleFailure(record, exception);
                } catch (RetryableException ignored) {}
            }

            // Third attempt sends to DLQ
            dlqHandler.handleFailure(record, exception);

            verify(kafkaTemplate).send(any(ProducerRecord.class));
        }
    }

    @Nested
    @DisplayName("DlqMessage Builder")
    class DlqMessageBuilderTest {

        @Test
        @DisplayName("should build complete DLQ message")
        void shouldBuildCompleteMessage() {
            DlqMessage message = DlqMessage.builder()
                    .originalTopic("test-topic")
                    .originalPartition(5)
                    .originalOffset(999L)
                    .originalKey("test-key")
                    .originalValue("{\"test\":true}")
                    .failureReason("Test failure")
                    .failureType(FailureType.VALIDATION_ERROR)
                    .hostname("test-host")
                    .stackTrace("at Test.method()")
                    .reprocessAttempts(2)
                    .build();

            assertThat(message.getOriginalTopic()).isEqualTo("test-topic");
            assertThat(message.getOriginalPartition()).isEqualTo(5);
            assertThat(message.getOriginalOffset()).isEqualTo(999L);
            assertThat(message.getOriginalKey()).isEqualTo("test-key");
            assertThat(message.getOriginalValue()).isEqualTo("{\"test\":true}");
            assertThat(message.getFailureReason()).isEqualTo("Test failure");
            assertThat(message.getFailureType()).isEqualTo(FailureType.VALIDATION_ERROR);
            assertThat(message.getHostname()).isEqualTo("test-host");
            assertThat(message.getStackTrace()).isEqualTo("at Test.method()");
            assertThat(message.getReprocessAttempts()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("FailureType Coverage")
    class FailureTypeCoverage {

        @Test
        @DisplayName("should have all failure types")
        void shouldHaveAllFailureTypes() {
            assertThat(FailureType.values()).containsExactlyInAnyOrder(
                    FailureType.VALIDATION_ERROR,
                    FailureType.DESERIALIZATION_ERROR,
                    FailureType.MAX_RETRIES_EXCEEDED,
                    FailureType.PERMANENT,
                    FailureType.REPROCESS_FAILED,
                    FailureType.UNKNOWN
            );
        }
    }

    // Helper methods
    private ConsumerRecord<String, String> createRecord(String topic, String key, String value) {
        return createRecord(topic, key, value, 0, 0L);
    }

    private ConsumerRecord<String, String> createRecord(String topic, String key, String value, int partition, long offset) {
        return new ConsumerRecord<>(topic, partition, offset, key, value);
    }

    @SuppressWarnings("unchecked")
    private void mockKafkaSend() {
        SendResult<String, String> sendResult = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("test.dlq", 0), 0L, 0, 0L, 0, 0
        );
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }
}
