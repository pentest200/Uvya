package com.uvya.apigateway.data.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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

    public MessageService(ChatRepository chatRepository, ChatMemberRepository memberRepository,
            MessageRepository messageRepository, UserInboxRepository inboxRepository,
            IdempotencyKeyRepository idempotencyRepository,
            OutboxEventRepository outboxRepository, DeviceRepository deviceRepository,
            OutboxEventFactory eventFactory, ObjectMapper objectMapper, AuditService auditService,
            ChatAuthorizationPolicy chatAuthorizationPolicy) {
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

        MessageEntity duplicate = messageRepository.findByChatIdAndClientMessageIdAndSenderId(
                command.chatId(), command.clientMessageId(), command.senderId()).orElse(null);
        if (duplicate != null) {
            return duplicate;
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

        validateReference(command.replyToMessageId(), chat.getId());
        validateReference(command.forwardedFromMessageId(), chat.getId());
        Instant now = Instant.now();
        long sequence = chat.nextMessageSequence(now);
        MessageEntity message = new MessageEntity(UUID.randomUUID(), chat.getId(), command.senderId(),
                command.senderDeviceId(), command.clientMessageId(), sequence, command.messageType(),
                command.body() == null ? "" : command.body(), command.replyToMessageId(),
                command.forwardedFromMessageId(), now);
        chatRepository.save(chat);
        messageRepository.save(message);
        memberRepository.findByIdChatIdAndLeftAtIsNullOrderByJoinedAtAsc(chat.getId()).forEach(member ->
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
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(),
                EventType.MESSAGE_CREATED.value(), 1, now, command.traceId(), idempotencyKey, payload),
                "MESSAGE", message.getMessageId()));
        auditService.record("MESSAGE_CREATED", command.senderId(), command.senderDeviceId(), null,
                new RequestContext(null, command.traceId(), null));
        return message;
    }

    private void validateReference(UUID messageId, UUID chatId) {
        if (messageId == null) {
            return;
        }
        MessageEntity reference = messageRepository.findById(messageId)
                .orElseThrow(() -> new DataFoundationException("Referenced message not found"));
        if (!chatId.equals(reference.getChatId())) {
            throw new DataFoundationException("Referenced message belongs to another chat");
        }
    }

    private String requestHash(MessageCreationCommand command) {
        String material = String.join("|", command.chatId().toString(), command.senderId().toString(),
                command.senderDeviceId().toString(), command.clientMessageId().toString(),
                String.valueOf(command.messageType()), String.valueOf(command.body()),
                String.valueOf(command.replyToMessageId()), String.valueOf(command.forwardedFromMessageId()));
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
