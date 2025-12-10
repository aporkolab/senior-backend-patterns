-- Outbox table for reliable event publishing
-- Used by the Outbox Pattern implementation

CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMP,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

-- Index for polling pending events efficiently
CREATE INDEX IF NOT EXISTS idx_outbox_pending 
    ON outbox_events (status, created_at) 
    WHERE status = 'PENDING';

-- Index for cleanup of old published events
CREATE INDEX IF NOT EXISTS idx_outbox_published_cleanup 
    ON outbox_events (published_at) 
    WHERE status = 'PUBLISHED';

-- Index for finding events by aggregate (useful for debugging)
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate 
    ON outbox_events (aggregate_type, aggregate_id);

COMMENT ON TABLE outbox_events IS 'Transactional outbox for reliable event publishing';
COMMENT ON COLUMN outbox_events.aggregate_type IS 'Type of aggregate that produced the event (e.g., Order, Payment)';
COMMENT ON COLUMN outbox_events.aggregate_id IS 'ID of the aggregate instance';
COMMENT ON COLUMN outbox_events.event_type IS 'Type of domain event (e.g., OrderCreated, PaymentCompleted)';
COMMENT ON COLUMN outbox_events.payload IS 'JSON-serialized event data';
COMMENT ON COLUMN outbox_events.status IS 'PENDING, PUBLISHED, or FAILED';
