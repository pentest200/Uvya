package com.uvya.apigateway.data.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Column;

@Embeddable
public class MessageReactionId implements Serializable {
    private UUID messageId;
    private UUID userId;
    @Column(name = "reaction_type", length = 64)
    private String reactionType;

    protected MessageReactionId() { }

    public MessageReactionId(UUID messageId, UUID userId, String reactionType) {
        this.messageId = messageId;
        this.userId = userId;
        this.reactionType = reactionType;
    }

    public UUID getMessageId() { return messageId; }
    public UUID getUserId() { return userId; }
    public String getReactionType() { return reactionType; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MessageReactionId that)) {
            return false;
        }
        return Objects.equals(messageId, that.messageId) && Objects.equals(userId, that.userId)
                && Objects.equals(reactionType, that.reactionType);
    }

    @Override
    public int hashCode() { return Objects.hash(messageId, userId, reactionType); }
}
