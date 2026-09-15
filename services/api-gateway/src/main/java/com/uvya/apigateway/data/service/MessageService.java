package com.uvya.apigateway.data.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.auth.domain.DeviceEntity;
import com.uvya.apigateway.auth.repository.DeviceRepository;
import com.uvya.apigateway.auth.service.AuditService;
import com.uvya.apigateway.auth.service.RequestContext;
import com.uvya.apigateway.chat.service.ChatAuthorizationPolicy;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.ChatMemberEntity;
import com.uvya.apigateway.data.domain.ChatType;
import com.uvya.apigateway.data.domain.IdempotencyKeyEntity;
import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.ChatRepository;
import com.uvya.apigateway.data.repository.IdempotencyKeyRepository;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;
import com.uvya.apigateway.data.repository.UserInboxRepository;
import com.uvya.apigateway.events.config.FanoutProperties;

@Service
public class MessageService {
    private final ChatRepository chatRepository;
    private final ChatMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final UserInboxRepository inboxRepository;
    private final IdempotencyKeyRepository idempotencyRepository;
    private final OutboxEventRepository outboxRepository;
    private final DeviceRepository deviceRepository;
    private final OutboxEventFactory eventFactory;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final ChatAuthorizationPolicy chatAuthorizationPolicy;
    private final FanoutProperties fanoutProperties;

    public MessageService(ChatRepository chatRepository, ChatMemberRepository memberRepository,
            MessageRepository messageRepository, UserInboxRepository inboxRepository,
            IdempotencyKeyRepository idempotencyRepository,
            OutboxEventRepository outboxRepository, DeviceRepository deviceRepository,
            OutboxEventFactory eventFactory, ObjectMapper objectMapper, AuditService auditService,
            ChatAuthorizationPolicy chatAuthorizationPolicy, FanoutProperties fanoutProperties) {
        this.chatRepository = chatRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.inboxRepository = inboxRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxRepository = outboxRepository;
        this.deviceRepository = deviceRepository;
        this.eventFactory = eventFactory;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.chatAuthorizationPolicy = chatAuthorizationPolicy;
        this.fanoutProperties = fanoutProperties;
    }

    @Transactional
    public MessageEntity create(MessageCreationCommand command) {
        requireId(command.chatId(), "chatId");
        requireId(command.senderId(), "senderId");
        requireId(command.senderDeviceId(), "senderDeviceId");
        requireId(command.clientMessageId(), "clientMessageId");
        requireText(command.messageType(), "messageType");
        requireText(command.traceId(), "traceId");

        ChatEntity chat = chatRepository.findByIdForUpdate(command.chatId())
                .orElseThrow(() -> new DataFoundationException("Chat not found"));
        try {
            chatAuthorizationPolicy.authorizeInternalPost(command.senderId(), command.senderDeviceId(), chat);
        } catch (com.uvya.apigateway.chat.service.ChatAuthorizationException exception) {
            throw new DataFoundationException(exception.getMessage());
        }
        if (!memberRepository.isActiveMember(command.chatId(), command.senderId())) {
            throw new DataFoundationException("User is not an active chat member");
        }
        DeviceEntity device = deviceRepository.findByIdAndUserId(command.senderDeviceId(), command.senderId())
                .orElseThrow(() -> new DataFoundationException("Sender device not found"));
        if (device.getRevokedAt() != null) {
            throw new DataFoundationException("Sender device is revoked");
        }

        String idempotencyKey = command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                ? command.clientMessageId().toString() : command.idempotencyKey();
        String requestHash = requestHash(command);
        IdempotencyKeyEntity previous = idempotencyRepository.findByUserIdAndIdempotencyKey(
                command.senderId(), idempotencyKey).orElse(null);
        if (previous != null) {
            if (!previous.getRequestHash().equals(requestHash)) {
                throw new DataFoundationException("Idempotency key was used for another request");
            }
            if (previous.getResourceId() != null) {
                return messageRepository.findById(previous.getResourceId())
                        .orElseThrow(() -> new DataFoundationException("Idempotency resource not found"));
            }
            throw new DataFoundationException("Idempotency request is incomplete");
        }

        MessageEntity duplicate = messageRepository.findByChatIdAndClientMessageIdAndSenderId(
                command.chatId(), command.clientMessageId(), command.senderId()).orElse(null);
        if (duplicate != null) {
            if (!messageMatches(command, duplicate)) {
                throw new DataFoundationException("Client message ID was reused for another message");
            }
            return duplicate;
        }

        MessageEntity reply = validateReply(command.replyToMessageId(), chat.getId());
        MessageEntity forwarded = validateForward(command.forwardedFromMessageId(), command.senderId());
        UUID threadRootMessageId = validateThreadRoot(command.threadRootMessageId(), chat.getId());
        Instant now = Instant.now();
        long sequence = chat.nextMessageSequence(now);
        MessageEntity message = new MessageEntity(UUID.randomUUID(), chat.getId(), command.senderId(),
                command.senderDeviceId(), command.clientMessageId(), sequence, command.messageType(),
                command.body() == null ? "" : command.body(), command.replyToMessageId(),
                command.forwardedFromMessageId(), threadRootMessageId,
                forwarded == null ? null : forwarded.getChatId(),
                forwarded == null ? null : forwarded.getSenderId(),
                forwarded == null ? null : forwarded.getCreatedAt(), now);
        chatRepository.save(chat);
        messageRepository.save(message);
        long memberCount = memberRepository.countByIdChatIdAndLeftAtIsNull(chat.getId());
        boolean fanoutOnWrite = chat.getChatType() == ChatType.DIRECT
                || memberCount <= fanoutProperties.getSmallGroupMemberLimit();
        List<ChatMemberEntity> activeMembers = fanoutOnWrite
                ? memberRepository.findByIdChatIdAndLeftAtIsNullOrderByJoinedAtAsc(chat.getId()) : List.of();
        activeMembers.forEach(member ->
                inboxRepository.save(new com.uvya.apigateway.data.domain.UserInboxEntity(member.getUserId(),
                        message.getMessageId(), message.getChatId(), message.getSequenceNumber(), now)));
        idempotencyRepository.save(new IdempotencyKeyEntity(UUID.randomUUID(), command.senderId(),
                idempotencyKey, requestHash, "MESSAGE", message.getMessageId(), now,
                now.plus(24, ChronoUnit.HOURS)));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", message.getMessageId().toString());
        payload.put("chatId", message.getChatId().toString());
        payload.put("senderId", message.getSenderId().toString());
        payload.put("senderDeviceId", message.getSenderDeviceId().toString());
        payload.put("clientMessageId", message.getClientMessageId().toString());
        payload.put("sequence", message.getSequenceNumber());
        payload.put("messageType", message.getMessageType());
        payload.put("body", message.getBody());
        putNullable(payload, "replyToMessageId", message.getReplyToMessageId());
        putNullable(payload, "forwardedFromMessageId", message.getForwardedFromMessageId());
        payload.put("version", message.getVersion());
        payload.put("status", message.getStatus().name());
        putNullable(payload, "threadRootMessageId", message.getThreadRootMessageId());
        putNullable(payload, "forwardedFromChatId", message.getForwardedFromChatId());
        putNullable(payload, "forwardedFromSenderId", message.getForwardedFromSenderId());
        putNullableInstant(payload, "forwardedFromCreatedAt", message.getForwardedFromCreatedAt());
        payload.put("replyToDeleted", reply != null
                && reply.getStatus() == com.uvya.apigateway.data.domain.MessageStatus.DELETED);
        payload.put("fanoutMode", fanoutOnWrite ? "WRITE" : "READ");
        if (fanoutOnWrite) {
            var recipientUserIds = payload.putArray("recipientUserIds");
            activeMembers.forEach(member -> recipientUserIds.add(member.getUserId().toString()));
        }
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(),
                EventType.MESSAGE_CREATED.value(), 1, now, command.traceId(), idempotencyKey, payload),
                "MESSAGE", message.getMessageId()));
        auditService.record("MESSAGE_CREATED", command.senderId(), command.senderDeviceId(), null,
                new RequestContext(null, command.traceId(), null));
        return message;
    }

    private MessageEntity validateReply(UUID messageId, UUID chatId) {
        if (messageId == null) {
            return null;
        }
        MessageEntity reference = messageRepository.findById(messageId)
                .orElseThrow(() -> new DataFoundationException("Referenced message not found"));
        if (!chatId.equals(reference.getChatId())) {
            throw new DataFoundationException("Referenced message belongs to another chat");
        }
        // A soft-deleted message remains a valid reference. Consumers receive
        // replyToDeleted and can render a tombstone without dereferencing body.
        return reference;
    }

    private MessageEntity validateForward(UUID messageId, UUID senderId) {
        if (messageId == null) {
            return null;
        }
        MessageEntity reference = messageRepository.findById(messageId)
                .orElseThrow(() -> new DataFoundationException("Forwarded message not found"));
        if (reference.getStatus() == com.uvya.apigateway.data.domain.MessageStatus.DELETED
                || !memberRepository.isActiveMember(reference.getChatId(), senderId)) {
            throw new DataFoundationException("Forwarded message is not available");
        }
        return reference;
    }

    private UUID validateThreadRoot(UUID messageId, UUID chatId) {
        if (messageId == null) {
            return null;
        }
        MessageEntity root = messageRepository.findById(messageId)
                .orElseThrow(() -> new DataFoundationException("Thread root message not found"));
        if (!chatId.equals(root.getChatId())) {
            throw new DataFoundationException("Thread root belongs to another chat");
        }
        return root.getThreadRootMessageId() == null ? root.getMessageId() : root.getThreadRootMessageId();
    }

    private boolean messageMatches(MessageCreationCommand command, MessageEntity message) {
        return command.chatId().equals(message.getChatId())
                && command.senderId().equals(message.getSenderId())
                && command.senderDeviceId().equals(message.getSenderDeviceId())
                && command.messageType().equals(message.getMessageType())
                && java.util.Objects.equals(command.body() == null ? "" : command.body(), message.getBody())
                && java.util.Objects.equals(command.replyToMessageId(), message.getReplyToMessageId())
                && java.util.Objects.equals(command.forwardedFromMessageId(), message.getForwardedFromMessageId())
                && java.util.Objects.equals(command.threadRootMessageId(), message.getThreadRootMessageId());
    }

    private String requestHash(MessageCreationCommand command) {
        String material = String.join("|", command.chatId().toString(), command.senderId().toString(),
                command.senderDeviceId().toString(), command.clientMessageId().toString(),
                String.valueOf(command.messageType()), String.valueOf(command.body()),
                String.valueOf(command.replyToMessageId()), String.valueOf(command.forwardedFromMessageId()),
                String.valueOf(command.threadRootMessageId()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void putNullable(ObjectNode payload, String field, UUID value) {
        if (value == null) {
            payload.putNull(field);
        } else {
            payload.put(field, value.toString());
        }
    }

    private void putNullableInstant(ObjectNode payload, String field, Instant value) {
        if (value == null) {
            payload.putNull(field);
        } else {
            payload.put(field, value.toString());
        }
    }

    private void requireId(UUID value, String name) {
        if (value == null) {
            throw new DataFoundationException(name + " is required");
        }
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DataFoundationException(name + " is required");
        }
    }
}
