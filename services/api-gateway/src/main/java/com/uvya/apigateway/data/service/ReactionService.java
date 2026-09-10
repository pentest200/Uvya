package com.uvya.apigateway.data.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.domain.MessageReactionEntity;
import com.uvya.apigateway.data.domain.MessageReactionId;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.MessageReactionRepository;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;

@Service
public class ReactionService {
    private final MessageRepository messageRepository;
    private final ChatMemberRepository memberRepository;
    private final MessageReactionRepository reactionRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory eventFactory;
    private final ObjectMapper objectMapper;

    public ReactionService(MessageRepository messageRepository, ChatMemberRepository memberRepository,
            MessageReactionRepository reactionRepository, OutboxEventRepository outboxRepository,
            OutboxEventFactory eventFactory, ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.memberRepository = memberRepository;
        this.reactionRepository = reactionRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void add(UUID userId, UUID messageId, String reactionType, String traceId, String idempotencyKey) {
        MessageEntity message = message(messageId);
        requireMember(userId, message);
        MessageReactionId reactionId = new MessageReactionId(messageId, userId, reactionType);
        if (reactionRepository.existsById(reactionId)) {
            return;
        }
        reactionRepository.save(new MessageReactionEntity(messageId, userId, reactionType, Instant.now()));
        publish(EventType.MESSAGE_REACTION_ADDED, message, userId, reactionType, traceId, idempotencyKey);
    }

    @Transactional
    public void remove(UUID userId, UUID messageId, String reactionType, String traceId,
            String idempotencyKey) {
        MessageEntity message = message(messageId);
        requireMember(userId, message);
        MessageReactionId reactionId = new MessageReactionId(messageId, userId, reactionType);
        if (!reactionRepository.existsById(reactionId)) {
            return;
        }
        reactionRepository.deleteById(reactionId);
        publish(EventType.MESSAGE_REACTION_REMOVED, message, userId, reactionType, traceId, idempotencyKey);
    }

    private MessageEntity message(UUID messageId) {
        return messageRepository.findById(messageId)
                .orElseThrow(() -> new DataFoundationException("Message not found"));
    }

    private void requireMember(UUID userId, MessageEntity message) {
        if (!memberRepository.isActiveMember(message.getChatId(), userId)) {
            throw new DataFoundationException("User is not an active chat member");
        }
    }

    private void publish(EventType type, MessageEntity message, UUID userId, String reactionType,
            String traceId, String idempotencyKey) {
        if (traceId == null || traceId.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DataFoundationException("traceId and idempotencyKey are required");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", message.getMessageId().toString());
        payload.put("chatId", message.getChatId().toString());
        payload.put("userId", userId.toString());
        payload.put("reactionType", reactionType);
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(), type.value(), 1,
                Instant.now(), traceId, idempotencyKey, payload), "MESSAGE", message.getMessageId()));
    }
}
