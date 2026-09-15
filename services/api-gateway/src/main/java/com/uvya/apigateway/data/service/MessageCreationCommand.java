package com.uvya.apigateway.data.service;

import java.util.UUID;

public record MessageCreationCommand(UUID chatId, UUID senderId, UUID senderDeviceId,
        UUID clientMessageId, String messageType, String body, UUID replyToMessageId,
        UUID forwardedFromMessageId, UUID threadRootMessageId, String traceId, String idempotencyKey) {
    public MessageCreationCommand(UUID chatId, UUID senderId, UUID senderDeviceId,
            UUID clientMessageId, String messageType, String body, UUID replyToMessageId,
            UUID forwardedFromMessageId, String traceId, String idempotencyKey) {
        this(chatId, senderId, senderDeviceId, clientMessageId, messageType, body, replyToMessageId,
                forwardedFromMessageId, null, traceId, idempotencyKey);
    }
}
