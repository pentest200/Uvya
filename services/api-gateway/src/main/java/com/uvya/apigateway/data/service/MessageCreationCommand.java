package com.uvya.apigateway.data.service;

import java.util.UUID;

public record MessageCreationCommand(UUID chatId, UUID senderId, UUID senderDeviceId,
        UUID clientMessageId, String messageType, String body, UUID replyToMessageId,
        UUID forwardedFromMessageId, String traceId, String idempotencyKey) {
}
