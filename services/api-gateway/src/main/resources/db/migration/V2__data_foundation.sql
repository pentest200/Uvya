-- Phase 2 already owns app_users, devices, auth_sessions, and auth_audit_logs.
-- This migration adds only the missing Uvya messaging data foundation.

-- The primary key already guarantees device identity; this additive key lets the
-- message sender foreign key enforce device/user ownership as well.
ALTER TABLE devices
    ADD CONSTRAINT uq_devices_id_user UNIQUE (id, user_id);

CREATE TABLE chats (
    id UUID PRIMARY KEY,
    chat_type VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    title VARCHAR(255),
    created_by UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    last_message_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_chats_type CHECK (chat_type IN ('DIRECT', 'GROUP', 'CHANNEL')),
    CONSTRAINT ck_chats_last_message_sequence CHECK (last_message_sequence >= 0)
);

CREATE TABLE chat_members (
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    member_role VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMPTZ,
    PRIMARY KEY (chat_id, user_id),
    CONSTRAINT ck_chat_members_role CHECK (member_role IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT ck_chat_members_leave_after_join
        CHECK (left_at IS NULL OR left_at >= joined_at)
);

CREATE INDEX ix_chat_members_user_chat ON chat_members(user_id, chat_id);

CREATE TABLE messages (
    message_id UUID PRIMARY KEY,
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE RESTRICT,
    sender_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    sender_device_id UUID NOT NULL,
    client_message_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    reply_to_message_id UUID REFERENCES messages(message_id) ON DELETE SET NULL,
    forwarded_from_message_id UUID REFERENCES messages(message_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uq_messages_chat_sequence UNIQUE (chat_id, sequence),
    CONSTRAINT uq_messages_client_idempotency
        UNIQUE (chat_id, client_message_id, sender_id),
    CONSTRAINT fk_messages_sender_device_owner
        FOREIGN KEY (sender_device_id, sender_id) REFERENCES devices(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_messages_sequence CHECK (sequence > 0),
    CONSTRAINT ck_messages_version CHECK (version > 0),
    CONSTRAINT ck_messages_status CHECK (status IN ('ACTIVE', 'EDITED', 'DELETED')),
    CONSTRAINT ck_messages_edited_after_created
        CHECK (edited_at IS NULL OR edited_at >= created_at),
    CONSTRAINT ck_messages_deleted_after_created
        CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);

-- uq_messages_chat_sequence is also the ordered chat-message access path.

CREATE TABLE message_reactions (
    message_id UUID NOT NULL REFERENCES messages(message_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    reaction_type VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id, user_id, reaction_type)
);

CREATE INDEX ix_message_reactions_message ON message_reactions(message_id, created_at);

CREATE TABLE message_versions (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES messages(message_id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    edited_by UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    edited_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_versions_message_version UNIQUE (message_id, version),
    CONSTRAINT ck_message_versions_version CHECK (version > 0)
);

CREATE TABLE user_inbox (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES messages(message_id) ON DELETE CASCADE,
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, message_id),
    CONSTRAINT ck_user_inbox_sequence CHECK (sequence > 0),
    CONSTRAINT ck_user_inbox_delivered_after_received
        CHECK (delivered_at IS NULL OR delivered_at >= received_at)
);

CREATE INDEX ix_user_inbox_user_sequence ON user_inbox(user_id, chat_id, sequence);

CREATE TABLE read_states (
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    last_read_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (chat_id, user_id),
    CONSTRAINT ck_read_states_sequence CHECK (last_read_sequence >= 0)
);

CREATE INDEX ix_read_states_user_chat ON read_states(user_id, chat_id);

CREATE TABLE blocked_users (
    blocker_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    blocked_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CONSTRAINT ck_blocked_users_not_self CHECK (blocker_user_id <> blocked_user_id)
);

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_keys_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_idempotency_keys_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_idempotency_keys_expiry ON idempotency_keys(expires_at);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    event_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_outbox_events_type_key UNIQUE (event_type, idempotency_key),
    CONSTRAINT ck_outbox_events_version CHECK (event_version > 0),
    CONSTRAINT ck_outbox_events_attempts CHECK (publish_attempts >= 0)
);

CREATE INDEX ix_outbox_events_polling
    ON outbox_events(available_at, occurred_at, event_id)
    WHERE published_at IS NULL;

CREATE INDEX ix_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);

-- auth_audit_logs is the existing Phase 2 audit log and remains the unified audit store.
