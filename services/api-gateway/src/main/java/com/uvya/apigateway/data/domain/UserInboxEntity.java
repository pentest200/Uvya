package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_inbox")
public class UserInboxEntity {
    @EmbeddedId
    private UserInboxId id;
    @Column(name = "chat_id", nullable = false)
    private UUID chatId;
    @Column(nullable = false)
    private long sequence;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected UserInboxEntity() { }

    public UserInboxEntity(UUID userId, UUID messageId, UUID chatId, long sequence, Instant receivedAt) {
        id = new UserInboxId(userId, messageId);
        this.chatId = chatId;
        this.sequence = sequence;
        this.receivedAt = receivedAt;
    }

    public UserInboxId getId() { return id; }
    public UUID getUserId() { return id.getUserId(); }
    public UUID getMessageId() { return id.getMessageId(); }
    public UUID getChatId() { return chatId; }
    public long getSequence() { return sequence; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }

    public void markDelivered(Instant now) { deliveredAt = now; }
}
