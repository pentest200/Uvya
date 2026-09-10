package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "message_reactions")
public class MessageReactionEntity {
    @EmbeddedId
    private MessageReactionId id;
    @Column(nullable = false)
    private Instant createdAt;

    protected MessageReactionEntity() { }

    public MessageReactionEntity(UUID messageId, UUID userId, String reactionType, Instant createdAt) {
        id = new MessageReactionId(messageId, userId, reactionType);
        this.createdAt = createdAt;
    }

    public MessageReactionId getId() { return id; }
    public UUID getMessageId() { return id.getMessageId(); }
    public UUID getUserId() { return id.getUserId(); }
    public String getReactionType() { return id.getReactionType(); }
    public Instant getCreatedAt() { return createdAt; }
}
