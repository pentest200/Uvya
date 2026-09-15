package com.uvya.apigateway.message.web;

import java.time.Instant;
import java.util.UUID;

import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.domain.MessageStatus;

public record MessageResponse(UUID messageId, UUID chatId, UUID senderId, UUID senderDeviceId,
        UUID clientMessageId, long sequence, String type, String body, UUID replyToMessageId,
        UUID forwardedFromMessageId, Instant createdAt, Instant editedAt, Instant deletedAt,
        int version, MessageStatus status, UUID threadRootMessageId, UUID forwardedFromChatId,
        UUID forwardedFromSenderId, Instant forwardedFromCreatedAt, boolean replyToDeleted) {
    public static MessageResponse from(MessageEntity message) {
        return from(message, false);
    }

    public static MessageResponse from(MessageEntity message, boolean replyToDeleted) {
        String visibleBody = message.getStatus() == MessageStatus.DELETED ? null : message.getBody();
        return new MessageResponse(message.getMessageId(), message.getChatId(), message.getSenderId(),
                message.getSenderDeviceId(), message.getClientMessageId(), message.getSequenceNumber(),
                message.getMessageType(), visibleBody, message.getReplyToMessageId(),
                message.getForwardedFromMessageId(), message.getCreatedAt(), message.getEditedAt(),
                message.getDeletedAt(), message.getVersion(), message.getStatus(),
                message.getThreadRootMessageId(), message.getForwardedFromChatId(),
                message.getForwardedFromSenderId(), message.getForwardedFromCreatedAt(), replyToDeleted);
    }
}
