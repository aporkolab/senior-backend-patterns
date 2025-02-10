package com.aporkolab.patterns.exception;

/**
 * External service integration failures.
 */
public class IntegrationException extends TechnicalException {

    public IntegrationException(String service, String operation, Throwable cause) {
        super(
            "INTEGRATION_ERROR",
            String.format("Integration with '%s' failed during '%s': %s", service, operation, cause.getMessage()),
            cause
        );
        with("service", service);
        with("operation", operation);
    }

    public IntegrationException(String service, String operation, String reason) {
        super(
            "INTEGRATION_ERROR",
            String.format("Integration with '%s' failed during '%s': %s", service, operation, reason)
        );
        with("service", service);
        with("operation", operation);
    }

    public static IntegrationException paymentGateway(String operation, Throwable cause) {
        return new IntegrationException("PaymentGateway", operation, cause);
    }

    public static IntegrationException kafka(String operation, Throwable cause) {
        return new IntegrationException("Kafka", operation, cause);
    }

    public static IntegrationException externalApi(String apiName, String operation, Throwable cause) {
        return new IntegrationException(apiName, operation, cause);
    }
}
