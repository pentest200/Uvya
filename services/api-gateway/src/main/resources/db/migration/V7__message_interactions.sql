-- Message references and interaction metadata are additive. Reactions and pins
-- remain separate rows so a reaction never rewrites the message record.
ALTER TABLE messages
    ADD COLUMN thread_root_message_id UUID,
    ADD COLUMN forwarded_from_chat_id UUID,
    ADD COLUMN forwarded_from_sender_id UUID,
    ADD COLUMN forwarded_from_created_at TIMESTAMPTZ;

ALTER TABLE messages
    ADD CONSTRAINT fk_messages_thread_root
        FOREIGN KEY (thread_root_message_id) REFERENCES messages(message_id) ON DELETE SET NULL;

CREATE INDEX ix_messages_thread_root_sequence
    ON messages(chat_id, thread_root_message_id, sequence);

CREATE INDEX ix_messages_forwarded_from
    ON messages(forwarded_from_message_id);
