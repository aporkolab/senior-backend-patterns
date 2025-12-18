-- Database initialization for demo application

-- Outbox table (used by Order Service)
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    
    -- Indexes for efficient polling
    CONSTRAINT outbox_events_status_check CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox_events(status, created_at) 
    WHERE status = 'PENDING';

-- Orders table
CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY,
    customer_id VARCHAR(100) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    payment_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Payments table (for Payment Service)
CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Notifications table (for tracking sent notifications)
CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    recipient VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- DLQ tracking table
CREATE TABLE IF NOT EXISTS dlq_messages (
    id UUID PRIMARY KEY,
    original_topic VARCHAR(200) NOT NULL,
    original_key VARCHAR(500),
    original_value TEXT,
    failure_reason TEXT NOT NULL,
    failure_type VARCHAR(50) NOT NULL,
    reprocess_attempts INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
