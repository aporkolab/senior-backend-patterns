package com.aporkolab.patterns.messaging.dlq;

import java.time.Instant;

/**
 * Enriched message stored in the Dead Letter Queue.
 * Contains all context needed for debugging and reprocessing.
 */
public class DlqMessage {

    private String originalTopic;
    private int originalPartition;
    private long originalOffset;
    private String originalKey;
    private String originalValue;
    private Instant originalTimestamp;
    private String failureReason;
    private FailureType failureType;
    private Instant failedAt;
    private String hostname;
    private String stackTrace;
    private int reprocessAttempts;

    private DlqMessage() {}

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public String getOriginalTopic() { return originalTopic; }
    public int getOriginalPartition() { return originalPartition; }
    public long getOriginalOffset() { return originalOffset; }
    public String getOriginalKey() { return originalKey; }
    public String getOriginalValue() { return originalValue; }
    public Instant getOriginalTimestamp() { return originalTimestamp; }
    public String getFailureReason() { return failureReason; }
    public FailureType getFailureType() { return failureType; }
    public Instant getFailedAt() { return failedAt; }
    public String getHostname() { return hostname; }
    public String getStackTrace() { return stackTrace; }
    public int getReprocessAttempts() { return reprocessAttempts; }

    public static class Builder {
        private final DlqMessage message = new DlqMessage();

        public Builder originalTopic(String originalTopic) {
            message.originalTopic = originalTopic;
            return this;
        }

        public Builder originalPartition(int originalPartition) {
            message.originalPartition = originalPartition;
            return this;
        }

        public Builder originalOffset(long originalOffset) {
            message.originalOffset = originalOffset;
            return this;
        }

        public Builder originalKey(String originalKey) {
            message.originalKey = originalKey;
            return this;
        }

        public Builder originalValue(String originalValue) {
            message.originalValue = originalValue;
            return this;
        }

        public Builder originalTimestamp(Instant originalTimestamp) {
            message.originalTimestamp = originalTimestamp;
            return this;
        }

        public Builder failureReason(String failureReason) {
            message.failureReason = failureReason;
            return this;
        }

        public Builder failureType(FailureType failureType) {
            message.failureType = failureType;
            return this;
        }

        public Builder failedAt(Instant failedAt) {
            message.failedAt = failedAt;
            return this;
        }

        public Builder hostname(String hostname) {
            message.hostname = hostname;
            return this;
        }

        public Builder stackTrace(String stackTrace) {
            message.stackTrace = stackTrace;
            return this;
        }

        public Builder reprocessAttempts(int reprocessAttempts) {
            message.reprocessAttempts = reprocessAttempts;
            return this;
        }

        public DlqMessage build() {
            return message;
        }
    }
}
