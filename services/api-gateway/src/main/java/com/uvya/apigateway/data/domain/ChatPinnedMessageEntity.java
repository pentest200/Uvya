package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_pinned_messages")
@IdClass(ChatPinnedMessageId.class)
public class ChatPinnedMessageEntity {
    @Id
    @Column(name = "chat_id")
    private UUID chatId;
    @Id
    @Column(name = "message_id")
    private UUID messageId;
    @Column(name = "pinned_by", nullable = false)
    private UUID pinnedBy;
    @Column(nullable = false)
    private Instant pinnedAt;

    protected ChatPinnedMessageEntity() { }

    public ChatPinnedMessageEntity(UUID chatId, UUID messageId, UUID pinnedBy, Instant pinnedAt) {
        this.chatId = chatId;
        this.messageId = messageId;
        this.pinnedBy = pinnedBy;
        this.pinnedAt = pinnedAt;
    }

    public UUID getChatId() { return chatId; }
    public UUID getMessageId() { return messageId; }
    public UUID getPinnedBy() { return pinnedBy; }
    public Instant getPinnedAt() { return pinnedAt; }
}
