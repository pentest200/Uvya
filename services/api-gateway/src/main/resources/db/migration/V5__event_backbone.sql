-- Kafka publication and consumption metadata. The outbox remains the durable
-- hand-off from a database transaction to the asynchronous event backbone.
ALTER TABLE outbox_events
    ADD COLUMN correlation_id VARCHAR(128);

UPDATE outbox_events
    SET correlation_id = trace_id
    WHERE correlation_id IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN correlation_id SET NOT NULL;

CREATE TABLE processed_events (
    id UUID PRIMARY KEY,
    consumer_group VARCHAR(255) NOT NULL,
    event_id UUID NOT NULL,
    topic VARCHAR(255) NOT NULL,
    partition_number INTEGER NOT NULL,
    offset_number BIGINT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_processed_events_group_event UNIQUE (consumer_group, event_id),
    CONSTRAINT ck_processed_events_partition CHECK (partition_number >= 0),
    CONSTRAINT ck_processed_events_offset CHECK (offset_number >= 0)
);

CREATE INDEX ix_processed_events_processed_at ON processed_events(processed_at);
