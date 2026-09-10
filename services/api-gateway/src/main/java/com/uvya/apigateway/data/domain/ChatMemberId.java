package com.uvya.apigateway.data.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Embeddable;

@Embeddable
public class ChatMemberId implements Serializable {
    private UUID chatId;
    private UUID userId;

    protected ChatMemberId() { }

    public ChatMemberId(UUID chatId, UUID userId) {
        this.chatId = chatId;
        this.userId = userId;
    }

    public UUID getChatId() { return chatId; }
    public UUID getUserId() { return userId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChatMemberId that)) {
            return false;
        }
        return Objects.equals(chatId, that.chatId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() { return Objects.hash(chatId, userId); }
}
