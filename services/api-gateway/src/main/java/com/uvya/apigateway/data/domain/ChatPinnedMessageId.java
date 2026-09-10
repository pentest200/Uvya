package com.uvya.apigateway.data.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ChatPinnedMessageId implements Serializable {
    private UUID chatId;
    private UUID messageId;

    public ChatPinnedMessageId() { }

    public ChatPinnedMessageId(UUID chatId, UUID messageId) {
        this.chatId = chatId;
        this.messageId = messageId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChatPinnedMessageId that)) {
            return false;
        }
        return Objects.equals(chatId, that.chatId) && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() { return Objects.hash(chatId, messageId); }
}
