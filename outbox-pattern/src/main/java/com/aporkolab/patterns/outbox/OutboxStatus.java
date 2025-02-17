package com.aporkolab.patterns.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
