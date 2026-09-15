package com.uvya.apigateway.chat.service;

import java.time.Instant;
import java.util.UUID;

import com.uvya.apigateway.data.domain.ChatPinnedMessageEntity;

public record PinnedMessageResponse(UUID chatId, UUID messageId, UUID pinnedBy, Instant pinnedAt) {
    public static PinnedMessageResponse from(ChatPinnedMessageEntity pin) {
        return new PinnedMessageResponse(pin.getChatId(), pin.getMessageId(), pin.getPinnedBy(), pin.getPinnedAt());
    }
}
