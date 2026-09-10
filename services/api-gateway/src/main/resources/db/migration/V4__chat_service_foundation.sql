-- Chat Service extensions build on the Phase 1 chat and membership tables.
ALTER TABLE chat_members
    DROP CONSTRAINT IF EXISTS ck_chat_members_role;

ALTER TABLE chat_members
    ADD CONSTRAINT ck_chat_members_role
    CHECK (member_role IN ('OWNER', 'ADMIN', 'MODERATOR', 'MEMBER', 'RESTRICTED'));

ALTER TABLE chat_members
    ADD COLUMN muted_until TIMESTAMPTZ,
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN banned_at TIMESTAMPTZ,
    ADD COLUMN banned_until TIMESTAMPTZ;

ALTER TABLE chat_members
    ADD CONSTRAINT ck_chat_members_ban_window
    CHECK (banned_at IS NULL OR banned_until IS NULL OR banned_until >= banned_at);

CREATE INDEX ix_chat_members_chat_active_role
    ON chat_members(chat_id, left_at, member_role);

ALTER TABLE messages
    ADD CONSTRAINT uq_messages_id_chat UNIQUE (message_id, chat_id);

CREATE TABLE chat_settings (
    chat_id UUID PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,
    member_posting_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    discoverable BOOLEAN NOT NULL DEFAULT FALSE,
    slow_mode_seconds INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_chat_settings_slow_mode CHECK (slow_mode_seconds >= 0)
);

CREATE TABLE chat_pinned_messages (
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    message_id UUID NOT NULL,
    pinned_by UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    pinned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (chat_id, message_id),
    CONSTRAINT fk_chat_pinned_message_chat
        FOREIGN KEY (message_id, chat_id) REFERENCES messages(message_id, chat_id) ON DELETE CASCADE
);

CREATE INDEX ix_chat_pinned_messages_chat_time
    ON chat_pinned_messages(chat_id, pinned_at DESC);
