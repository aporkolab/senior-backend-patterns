package com.aporkolab.patterns.logging;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

/**
 * Kafka interceptors for correlation ID propagation.
 * 
 * ProducerInterceptor: Adds correlation ID from MDC to message headers
 * ConsumerInterceptor: Extracts correlation ID from headers to MDC
 * 
 * Configuration:
 * <pre>
 * spring:
 *   kafka:
 *     producer:
 *       properties:
 *         interceptor.classes: com.aporkolab.patterns.logging.KafkaCorrelationInterceptor$Producer
 *     consumer:
 *       properties:
 *         interceptor.classes: com.aporkolab.patterns.logging.KafkaCorrelationInterceptor$Consumer
 * </pre>
 */
public class KafkaCorrelationInterceptor {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String SOURCE_SERVICE_HEADER = "X-Source-Service";

    /**
     * Producer interceptor that adds correlation ID to outgoing messages.
     */
    public static class Producer implements ProducerInterceptor<Object, Object> {

        @Override
        public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
            String correlationId = MDC.get(CorrelationContext.CORRELATION_ID_KEY);
            if (correlationId != null) {
                record.headers().add(CORRELATION_ID_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
            }

            String serviceName = MDC.get(CorrelationContext.SERVICE_NAME_KEY);
            if (serviceName != null) {
                record.headers().add(SOURCE_SERVICE_HEADER, serviceName.getBytes(StandardCharsets.UTF_8));
            }

            return record;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
            // No action needed
        }

        @Override
        public void close() {
            // No resources to clean up
        }

        @Override
        public void configure(Map<String, ?> configs) {
            // No configuration needed
        }
    }

    /**
     * Consumer interceptor that extracts correlation ID from incoming messages.
     */
    public static class Consumer implements ConsumerInterceptor<Object, Object> {

        @Override
        public ConsumerRecords<Object, Object> onConsume(ConsumerRecords<Object, Object> records) {
            // Note: MDC should be set per-message in the actual listener
            // This interceptor can log or perform batch-level operations
            return records;
        }

        @Override
        public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
            // No action needed
        }

        @Override
        public void close() {
            // No resources to clean up
        }

        @Override
        public void configure(Map<String, ?> configs) {
            // No configuration needed
        }

        /**
         * Utility method to extract correlation ID from a Kafka record.
         * Call this in your @KafkaListener method.
         */
        public static String extractCorrelationId(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record) {
            Header header = record.headers().lastHeader(CORRELATION_ID_HEADER);
            if (header != null) {
                return new String(header.value(), StandardCharsets.UTF_8);
            }
            return null;
        }

        /**
         * Utility method to set up correlation context from a Kafka record.
         */
        public static CorrelationContext setupContext(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record) {
            String correlationId = extractCorrelationId(record);
            return CorrelationContext.continueOrCreate(correlationId);
        }
    }
}
