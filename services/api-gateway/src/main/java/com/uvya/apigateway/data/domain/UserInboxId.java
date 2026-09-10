package com.uvya.apigateway.data.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Embeddable;

@Embeddable
public class UserInboxId implements Serializable {
    private UUID userId;
    private UUID messageId;

    protected UserInboxId() { }

    public UserInboxId(UUID userId, UUID messageId) {
        this.userId = userId;
        this.messageId = messageId;
    }

    public UUID getUserId() { return userId; }
    public UUID getMessageId() { return messageId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserInboxId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() { return Objects.hash(userId, messageId); }
}
