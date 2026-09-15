ALTER TABLE user_inbox
    ADD COLUMN delivery_state VARCHAR(16) NOT NULL DEFAULT 'PERSISTED',
    ADD COLUMN delivery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_delivery_attempt_at TIMESTAMPTZ;

ALTER TABLE user_inbox
    ADD CONSTRAINT ck_user_inbox_delivery_state
        CHECK (delivery_state IN ('PENDING', 'PERSISTED', 'DELIVERED', 'READ', 'FAILED')),
    ADD CONSTRAINT ck_user_inbox_delivery_attempts CHECK (delivery_attempts >= 0);

CREATE INDEX ix_user_inbox_pending_delivery
    ON user_inbox(delivery_state, last_delivery_attempt_at)
    WHERE delivery_state = 'PENDING';
