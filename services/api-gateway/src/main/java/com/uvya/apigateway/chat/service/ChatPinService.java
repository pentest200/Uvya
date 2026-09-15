package com.uvya.apigateway.chat.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.ChatPinnedMessageEntity;
import com.uvya.apigateway.data.domain.ChatPinnedMessageId;
import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.domain.MessageStatus;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatPinnedMessageRepository;
import com.uvya.apigateway.data.repository.ChatRepository;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;

@Service
public class ChatPinService {
    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final ChatPinnedMessageRepository pinRepository;
    private final ChatAuthorizationPolicy policy;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory eventFactory;
    private final ObjectMapper objectMapper;

    public ChatPinService(ChatRepository chatRepository, MessageRepository messageRepository,
            ChatPinnedMessageRepository pinRepository, ChatAuthorizationPolicy policy,
            OutboxEventRepository outboxRepository, OutboxEventFactory eventFactory, ObjectMapper objectMapper) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.pinRepository = pinRepository;
        this.policy = policy;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PinnedMessageResponse pin(ChatAccessContext context, UUID chatId, UUID messageId, String idempotencyKey) {
        ChatEntity chat = lockedChat(chatId);
        policy.authorize(context, chat, ChatAction.VIEW);
        MessageEntity message = message(messageId, chatId);
        if (message.getStatus() == MessageStatus.DELETED) {
            throw new ChatValidationException("Deleted messages cannot be pinned");
        }
        ChatPinnedMessageId id = new ChatPinnedMessageId(chatId, messageId);
        ChatPinnedMessageEntity existing = pinRepository.findById(id).orElse(null);
        if (existing != null) {
            return PinnedMessageResponse.from(existing);
        }
        Instant now = Instant.now();
        ChatPinnedMessageEntity pin = pinRepository.save(new ChatPinnedMessageEntity(chatId, messageId,
                context.userId(), now));
        emit(EventType.MESSAGE_PINNED, chatId, messageId, context.userId(), context.traceId(),
                key(idempotencyKey, "pin", chatId, messageId), now);
        return PinnedMessageResponse.from(pin);
    }

    @Transactional
    public void unpin(ChatAccessContext context, UUID chatId, UUID messageId, String idempotencyKey) {
        ChatEntity chat = lockedChat(chatId);
        policy.authorize(context, chat, ChatAction.VIEW);
        message(messageId, chatId);
        ChatPinnedMessageId id = new ChatPinnedMessageId(chatId, messageId);
        if (!pinRepository.existsById(id)) {
            return;
        }
        pinRepository.deleteById(id);
        emit(EventType.MESSAGE_UNPINNED, chatId, messageId, context.userId(), context.traceId(),
                key(idempotencyKey, "unpin", chatId, messageId), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<PinnedMessageResponse> list(ChatAccessContext context, UUID chatId) {
        ChatEntity chat = chatRepository.findById(chatId).orElseThrow(() -> new ChatNotFoundException());
        policy.authorize(context, chat, ChatAction.VIEW);
        return pinRepository.findTop50ByChatIdOrderByPinnedAtDesc(chatId).stream()
                .map(PinnedMessageResponse::from).toList();
    }

    private ChatEntity lockedChat(UUID chatId) {
        return chatRepository.findByIdForUpdate(chatId).orElseThrow(() -> new ChatNotFoundException());
    }

    private MessageEntity message(UUID messageId, UUID chatId) {
        MessageEntity message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ChatValidationException("Message not found"));
        if (!chatId.equals(message.getChatId())) {
            throw new ChatValidationException("Message does not belong to this chat");
        }
        return message;
    }

    private void emit(EventType type, UUID chatId, UUID messageId, UUID actorId, String traceId,
            String idempotencyKey, Instant occurredAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chatId", chatId.toString());
        payload.put("messageId", messageId.toString());
        payload.put("userId", actorId.toString());
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(), type.value(), 1,
                occurredAt, traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId,
                idempotencyKey, payload), "MESSAGE", messageId));
    }

    private String key(String supplied, String operation, UUID chatId, UUID messageId) {
        return supplied == null || supplied.isBlank()
                ? operation + ":" + chatId + ":" + messageId + ":" + UUID.randomUUID() : supplied;
    }
}
