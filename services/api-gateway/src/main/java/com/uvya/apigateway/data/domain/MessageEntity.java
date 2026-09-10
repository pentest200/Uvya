package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "messages", uniqueConstraints = {
        @UniqueConstraint(name = "uq_messages_chat_sequence", columnNames = {"chat_id", "sequence"}),
        @UniqueConstraint(name = "uq_messages_client_idempotency",
                columnNames = {"chat_id", "client_message_id", "sender_id"})
})
public class MessageEntity {
    @Id
    @Column(name = "message_id")
    private UUID messageId;
    @Column(name = "chat_id", nullable = false)
    private UUID chatId;
    @Column(name = "sender_id", nullable = false)
    private UUID senderId;
    @Column(name = "sender_device_id", nullable = false)
    private UUID senderDeviceId;
    @Column(name = "client_message_id", nullable = false)
    private UUID clientMessageId;
    @Column(name = "sequence", nullable = false)
    private long sequenceNumber;
    @Column(name = "message_type", nullable = false, length = 32)
    private String messageType;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;
    @Column(name = "reply_to_message_id")
    private UUID replyToMessageId;
    @Column(name = "forwarded_from_message_id")
    private UUID forwardedFromMessageId;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant editedAt;
    private Instant deletedAt;
    @Column(nullable = false)
    private int version;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageStatus status;

    protected MessageEntity() { }

    public MessageEntity(UUID messageId, UUID chatId, UUID senderId, UUID senderDeviceId,
            UUID clientMessageId, long sequenceNumber, String messageType, String body,
            UUID replyToMessageId, UUID forwardedFromMessageId, Instant createdAt) {
        this.messageId = messageId;
        this.chatId = chatId;
        this.senderId = senderId;
        this.senderDeviceId = senderDeviceId;
        this.clientMessageId = clientMessageId;
        this.sequenceNumber = sequenceNumber;
        this.messageType = messageType;
        this.body = body;
        this.replyToMessageId = replyToMessageId;
        this.forwardedFromMessageId = forwardedFromMessageId;
        this.createdAt = createdAt;
        this.version = 1;
        this.status = MessageStatus.ACTIVE;
    }

    public UUID getMessageId() { return messageId; }
    public UUID getChatId() { return chatId; }
    public UUID getSenderId() { return senderId; }
    public UUID getSenderDeviceId() { return senderDeviceId; }
    public UUID getClientMessageId() { return clientMessageId; }
    public long getSequenceNumber() { return sequenceNumber; }
    public String getMessageType() { return messageType; }
    public String getBody() { return body; }
    public UUID getReplyToMessageId() { return replyToMessageId; }
    public UUID getForwardedFromMessageId() { return forwardedFromMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEditedAt() { return editedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public int getVersion() { return version; }
    public MessageStatus getStatus() { return status; }

    public void edit(String newMessageType, String newBody, Instant now) {
        messageType = newMessageType;
        body = newBody;
        version++;
        editedAt = now;
        status = MessageStatus.EDITED;
    }

    public void delete(Instant now) {
        deletedAt = now;
        editedAt = now;
        version++;
        status = MessageStatus.DELETED;
    }
}
