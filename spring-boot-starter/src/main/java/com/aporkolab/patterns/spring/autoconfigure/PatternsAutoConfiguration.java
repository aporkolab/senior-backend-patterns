package com.aporkolab.patterns.spring.autoconfigure;

import com.aporkolab.patterns.metrics.CircuitBreakerMetrics;
import com.aporkolab.patterns.metrics.DlqMetrics;
import com.aporkolab.patterns.metrics.HttpClientMetrics;
import com.aporkolab.patterns.metrics.OutboxMetrics;
import com.aporkolab.patterns.ratelimiter.RateLimiter;
import com.aporkolab.patterns.resilience.circuitbreaker.CircuitBreaker;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Spring Boot Auto-Configuration for Senior Backend Patterns.
 * 
 * Automatically configures:
 * - Circuit Breaker with metrics
 * - Rate Limiter with configurable algorithm
 * - Outbox Pattern metrics
 * - DLQ metrics
 * - HTTP Client metrics
 * 
 * Enable with: patterns.enabled=true in application.properties
 */
@AutoConfiguration
@EnableConfigurationProperties(PatternsProperties.class)
@ConditionalOnProperty(prefix = "patterns", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PatternsAutoConfiguration {

    // ==================== CIRCUIT BREAKER ====================

    @Configuration
    @ConditionalOnClass(CircuitBreaker.class)
    @ConditionalOnProperty(prefix = "patterns.circuit-breaker", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class CircuitBreakerAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "defaultCircuitBreaker")
        public CircuitBreaker defaultCircuitBreaker(PatternsProperties properties) {
            var config = properties.getCircuitBreaker();
            return CircuitBreaker.builder()
                    .name(config.getName())
                    .failureThreshold(config.getFailureThreshold())
                    .successThreshold(config.getSuccessThreshold())
                    .openDurationMs(config.getOpenDurationMs())
                    .build();
        }

        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        @ConditionalOnMissingBean(CircuitBreakerMetrics.class)
        public CircuitBreakerMetrics circuitBreakerMetrics(
                CircuitBreaker defaultCircuitBreaker, 
                MeterRegistry registry) {
            return CircuitBreakerMetrics.of(defaultCircuitBreaker, registry);
        }
    }

    // ==================== RATE LIMITER ====================

    @Configuration
    @ConditionalOnClass(RateLimiter.class)
    @ConditionalOnProperty(prefix = "patterns.rate-limiter", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RateLimiterAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "defaultRateLimiter")
        public RateLimiter defaultRateLimiter(PatternsProperties properties) {
            var config = properties.getRateLimiter();
            
            return switch (config.getAlgorithm()) {
                case TOKEN_BUCKET -> RateLimiter.tokenBucket()
                        .name(config.getName())
                        .capacity(config.getCapacity())
                        .refillRate(config.getRefillRate())
                        .refillPeriod(Duration.ofMillis(config.getRefillPeriodMs()))
                        .build();
                case SLIDING_WINDOW -> RateLimiter.slidingWindow()
                        .name(config.getName())
                        .maxRequests(config.getMaxRequests())
                        .windowSize(Duration.ofMillis(config.getWindowSizeMs()))
                        .build();
                case FIXED_WINDOW -> RateLimiter.fixedWindow()
                        .name(config.getName())
                        .maxRequests(config.getMaxRequests())
                        .windowSize(Duration.ofMillis(config.getWindowSizeMs()))
                        .build();
            };
        }
    }

    // ==================== METRICS ====================

    @Configuration
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "patterns.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class MetricsAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public OutboxMetrics outboxMetrics(MeterRegistry registry) {
            return new OutboxMetrics(registry);
        }

        @Bean
        @ConditionalOnMissingBean
        public DlqMetrics dlqMetrics(MeterRegistry registry, PatternsProperties properties) {
            return new DlqMetrics(registry, properties.getDlq().getName());
        }

        @Bean
        @ConditionalOnMissingBean
        public HttpClientMetrics httpClientMetrics(MeterRegistry registry, PatternsProperties properties) {
            return new HttpClientMetrics(registry, properties.getHttpClient().getName());
        }
    }
}
