package com.uvya.apigateway.data.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
    public ReactionState add(UUID userId, UUID messageId, String reactionType, String traceId, String idempotencyKey) {
        MessageEntity message = lockedMessage(messageId);
        requireMember(userId, message);
        validateReactionType(reactionType);
        MessageReactionId reactionId = new MessageReactionId(messageId, userId, reactionType);
        if (!reactionRepository.existsById(reactionId)) {
            reactionRepository.save(new MessageReactionEntity(messageId, userId, reactionType, Instant.now()));
            ReactionState state = state(userId, messageId);
            publish(EventType.MESSAGE_REACTION_ADDED, message, userId, reactionType, traceId, idempotencyKey, state);
            return state;
        }
        return state(userId, messageId);
    }

    @Transactional
    public ReactionState remove(UUID userId, UUID messageId, String reactionType, String traceId,
            String idempotencyKey) {
        MessageEntity message = lockedMessage(messageId);
        requireMember(userId, message);
        validateReactionType(reactionType);
        MessageReactionId reactionId = new MessageReactionId(messageId, userId, reactionType);
        if (reactionRepository.existsById(reactionId)) {
            reactionRepository.deleteById(reactionId);
            ReactionState state = state(userId, messageId);
            publish(EventType.MESSAGE_REACTION_REMOVED, message, userId, reactionType, traceId, idempotencyKey, state);
            return state;
        }
        return state(userId, messageId);
    }

    @Transactional(readOnly = true)
    public ReactionState state(UUID userId, UUID messageId) {
        MessageEntity message = messageRepository.findById(messageId)
                .orElseThrow(() -> new DataFoundationException("Message not found"));
        requireMember(userId, message);
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (MessageReactionRepository.ReactionCount count : reactionRepository.countByMessageId(messageId)) {
            counts.put(count.getReactionType(), count.getReactionCount());
        }
        List<String> mine = reactionRepository.findByIdMessageIdAndIdUserIdOrderByCreatedAtAsc(messageId, userId)
                .stream().map(reaction -> reaction.getId().getReactionType()).toList();
        return new ReactionState(messageId, counts, mine);
    }

    private MessageEntity lockedMessage(UUID messageId) {
        return messageRepository.findByIdForUpdate(messageId)
                .orElseThrow(() -> new DataFoundationException("Message not found"));
    }

    private void requireMember(UUID userId, MessageEntity message) {
        if (!memberRepository.isActiveMember(message.getChatId(), userId)) {
            throw new DataFoundationException("User is not an active chat member");
        }
    }

    private void validateReactionType(String reactionType) {
        if (reactionType == null || reactionType.isBlank() || reactionType.length() > 64) {
            throw new DataFoundationException("Reaction must be between 1 and 64 characters");
        }
    }

    private void publish(EventType type, MessageEntity message, UUID userId, String reactionType,
            String traceId, String idempotencyKey, ReactionState state) {
        if (traceId == null || traceId.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DataFoundationException("traceId and idempotencyKey are required");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", message.getMessageId().toString());
        payload.put("chatId", message.getChatId().toString());
        payload.put("userId", userId.toString());
        payload.put("reactionType", reactionType);
        ObjectNode counts = payload.putObject("counts");
        state.counts().forEach(counts::put);
        var mine = payload.putArray("userReactions");
        state.myReactions().forEach(mine::add);
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(), type.value(), 1,
                Instant.now(), traceId, idempotencyKey, payload), "MESSAGE", message.getMessageId()));
    }
}
