package com.aporkolab.patterns.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Comprehensive tests for the Exception Framework.
 */
class ExceptionFrameworkTest {

    @Nested
    @DisplayName("DomainException Base Class")
    class DomainExceptionTest {

        @Test
        @DisplayName("should store code, message, and status")
        void shouldStoreBasicProperties() {
            NotFoundException exception = new NotFoundException("Order", "123");

            assertThat(exception.getCode()).isEqualTo("ORDER_NOT_FOUND");
            assertThat(exception.getMessage()).contains("Order");
            assertThat(exception.getMessage()).contains("123");
            assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("should have timestamp")
        void shouldHaveTimestamp() {
            NotFoundException exception = new NotFoundException("User", "456");

            assertThat(exception.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("should support context with fluent API")
        void shouldSupportContext() {
            NotFoundException exception = new NotFoundException("Payment", "pay-789");

            assertThat(exception.getContext()).containsEntry("resourceType", "Payment");
            assertThat(exception.getContext()).containsEntry("resourceId", "pay-789");
        }

        @Test
        @DisplayName("context should be immutable copy")
        void contextShouldBeImmutableCopy() {
            NotFoundException exception = new NotFoundException("Order", "123");
            
            assertThat(exception.getContext()).isNotSameAs(exception.getContext());
        }
    }

    @Nested
    @DisplayName("NotFoundException")
    class NotFoundExceptionTest {

        @Test
        @DisplayName("should format message correctly")
        void shouldFormatMessage() {
            NotFoundException exception = new NotFoundException("Product", "prod-abc");

            assertThat(exception.getMessage()).isEqualTo("Product not found: prod-abc");
        }

        @Test
        @DisplayName("should have factory methods for common types")
        void shouldHaveFactoryMethods() {
            assertThat(NotFoundException.order("o-1").getCode()).isEqualTo("ORDER_NOT_FOUND");
            assertThat(NotFoundException.user("u-1").getCode()).isEqualTo("USER_NOT_FOUND");
            assertThat(NotFoundException.payment("p-1").getCode()).isEqualTo("PAYMENT_NOT_FOUND");
        }

        @Test
        @DisplayName("should return 404 status")
        void shouldReturn404() {
            NotFoundException exception = NotFoundException.order("123");

            assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("ConflictException")
    class ConflictExceptionTest {

        @Test
        @DisplayName("should format message with reason")
        void shouldFormatMessageWithReason() {
            ConflictException exception = new ConflictException("Order", "123", "already shipped");

            assertThat(exception.getMessage()).contains("Order");
            assertThat(exception.getMessage()).contains("123");
            assertThat(exception.getMessage()).contains("already shipped");
        }

        @Test
        @DisplayName("should have duplicate factory method")
        void shouldHaveDuplicateFactory() {
            ConflictException exception = ConflictException.duplicate("Email", "test@example.com");

            assertThat(exception.getMessage()).contains("already exists");
            assertThat(exception.getCode()).isEqualTo("EMAIL_CONFLICT");
        }

        @Test
        @DisplayName("should have concurrent modification factory method")
        void shouldHaveConcurrentModificationFactory() {
            ConflictException exception = ConflictException.concurrentModification("Order", "o-1");

            assertThat(exception.getMessage()).contains("modified by another process");
        }

        @Test
        @DisplayName("should return 409 status")
        void shouldReturn409() {
            ConflictException exception = ConflictException.duplicate("User", "u-1");

            assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("should include reason in context")
        void shouldIncludeReasonInContext() {
            ConflictException exception = new ConflictException("Order", "123", "version mismatch");

            assertThat(exception.getContext()).containsEntry("reason", "version mismatch");
        }
    }

    @Nested
    @DisplayName("ValidationException")
    class ValidationExceptionTest {

        @Test
        @DisplayName("should handle single field error")
        void shouldHandleSingleFieldError() {
            ValidationException exception = new ValidationException("email", "must be valid email");

            assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(exception.getMessage()).contains("email");
            assertThat(exception.getMessage()).contains("must be valid email");
            assertThat(exception.getContext()).containsEntry("field", "email");
        }

        @Test
        @DisplayName("should handle multiple field errors")
        void shouldHandleMultipleFieldErrors() {
            List<ValidationException.FieldError> errors = List.of(
                    new ValidationException.FieldError("email", "required"),
                    new ValidationException.FieldError("password", "too short"),
                    new ValidationException.FieldError("age", "must be positive")
            );

            ValidationException exception = new ValidationException(errors);

            assertThat(exception.getMessage()).contains("multiple fields");
            assertThat(exception.getContext()).containsKey("errors");
        }

        @Test
        @DisplayName("should return 400 status")
        void shouldReturn400() {
            ValidationException exception = new ValidationException("field", "error");

            assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("FieldError record should work correctly")
        void fieldErrorRecordShouldWork() {
            ValidationException.FieldError error = new ValidationException.FieldError("username", "already taken");

            assertThat(error.field()).isEqualTo("username");
            assertThat(error.message()).isEqualTo("already taken");
        }
    }

    @Nested
    @DisplayName("IntegrationException")
    class IntegrationExceptionTest {

        @Test
        @DisplayName("should include service and operation")
        void shouldIncludeServiceAndOperation() {
            IntegrationException exception = new IntegrationException(
                    "PaymentGateway", "charge", new RuntimeException("timeout")
            );

            assertThat(exception.getMessage()).contains("PaymentGateway");
            assertThat(exception.getMessage()).contains("charge");
            assertThat(exception.getMessage()).contains("timeout");
            assertThat(exception.getContext()).containsEntry("service", "PaymentGateway");
            assertThat(exception.getContext()).containsEntry("operation", "charge");
        }

        @Test
        @DisplayName("should have factory methods")
        void shouldHaveFactoryMethods() {
            IntegrationException paymentEx = IntegrationException.paymentGateway(
                    "refund", new RuntimeException("declined")
            );
            IntegrationException kafkaEx = IntegrationException.kafka(
                    "publish", new RuntimeException("broker down")
            );
            IntegrationException apiEx = IntegrationException.externalApi(
                    "WeatherAPI", "getForecast", new RuntimeException("rate limited")
            );

            assertThat(paymentEx.getContext()).containsEntry("service", "PaymentGateway");
            assertThat(kafkaEx.getContext()).containsEntry("service", "Kafka");
            assertThat(apiEx.getContext()).containsEntry("service", "WeatherAPI");
        }

        @Test
        @DisplayName("should return 500 status")
        void shouldReturn500() {
            IntegrationException exception = new IntegrationException("Service", "op", "error");

            assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("should support string reason without cause")
        void shouldSupportStringReason() {
            IntegrationException exception = new IntegrationException("Cache", "get", "connection refused");

            assertThat(exception.getMessage()).contains("connection refused");
            assertThat(exception.getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("TechnicalException")
    class TechnicalExceptionTest {

        @Test
        @DisplayName("should default to 500 status")
        void shouldDefaultTo500() {
            IntegrationException exception = new IntegrationException("DB", "query", "timeout");

            assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("BusinessException")
    class BusinessExceptionTest {

        @Test
        @DisplayName("all subclasses should return 4xx status")
        void allSubclassesShouldReturn4xx() {
            assertThat(new NotFoundException("T", "1").getHttpStatus().is4xxClientError()).isTrue();
            assertThat(ConflictException.duplicate("T", "1").getHttpStatus().is4xxClientError()).isTrue();
            assertThat(new ValidationException("f", "m").getHttpStatus().is4xxClientError()).isTrue();
        }
    }

    @Nested
    @DisplayName("GlobalExceptionHandler")
    class GlobalExceptionHandlerTest {

        private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

        @Test
        @DisplayName("should handle BusinessException and expose details")
        void shouldHandleBusinessException() {
            NotFoundException exception = NotFoundException.order("order-123");

            var response = handler.handleBusinessException(exception);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().code()).isEqualTo("ORDER_NOT_FOUND");
            assertThat(response.getBody().message()).contains("order-123");
            assertThat(response.getBody().details()).isNotEmpty();
        }

        @Test
        @DisplayName("should handle TechnicalException and hide details")
        void shouldHandleTechnicalException() {
            IntegrationException exception = IntegrationException.kafka(
                    "publish", new RuntimeException("sensitive error details")
            );

            var response = handler.handleTechnicalException(exception);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().message()).doesNotContain("sensitive");
            assertThat(response.getBody().details()).isEmpty();
        }

        @Test
        @DisplayName("should handle unexpected exceptions")
        void shouldHandleUnexpectedException() {
            Exception exception = new NullPointerException("oops");

            var response = handler.handleUnexpectedException(exception);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().code()).isEqualTo("UNEXPECTED_ERROR");
            assertThat(response.getBody().message()).doesNotContain("oops");
        }

        @Test
        @DisplayName("ErrorResponse should have factory method")
        void errorResponseShouldHaveFactoryMethod() {
            GlobalExceptionHandler.ErrorResponse response = 
                    GlobalExceptionHandler.ErrorResponse.generic("Something went wrong");

            assertThat(response.code()).isEqualTo("ERROR");
            assertThat(response.message()).isEqualTo("Something went wrong");
            assertThat(response.timestamp()).isNotNull();
            assertThat(response.details()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Exception Hierarchy")
    class ExceptionHierarchy {

        @Test
        @DisplayName("BusinessException extends DomainException")
        void businessExceptionExtendsDomain() {
            assertThat(new NotFoundException("T", "1")).isInstanceOf(DomainException.class);
        }

        @Test
        @DisplayName("TechnicalException extends DomainException")
        void technicalExceptionExtendsDomain() {
            assertThat(new IntegrationException("S", "O", "R")).isInstanceOf(DomainException.class);
        }

        @Test
        @DisplayName("NotFoundException extends BusinessException")
        void notFoundExtendsBusinessException() {
            assertThat(new NotFoundException("T", "1")).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("IntegrationException extends TechnicalException")
        void integrationExtendsTechnicalException() {
            assertThat(new IntegrationException("S", "O", "R")).isInstanceOf(TechnicalException.class);
        }
    }
}
