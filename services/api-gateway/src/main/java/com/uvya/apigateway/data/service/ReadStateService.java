package com.uvya.apigateway.data.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.data.domain.ReadStateEntity;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;
import com.uvya.apigateway.data.repository.ReadStateRepository;

@Service
public class ReadStateService {
    private final ChatMemberRepository memberRepository;
    private final ReadStateRepository readStateRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory eventFactory;
    private final ObjectMapper objectMapper;

    public ReadStateService(ChatMemberRepository memberRepository, ReadStateRepository readStateRepository,
            OutboxEventRepository outboxRepository, OutboxEventFactory eventFactory,
            ObjectMapper objectMapper) {
        this.memberRepository = memberRepository;
        this.readStateRepository = readStateRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReadStateEntity markRead(UUID userId, UUID chatId, long sequence, String traceId,
            String idempotencyKey) {
        if (sequence < 0 || !memberRepository.isActiveMember(chatId, userId)) {
            throw new DataFoundationException("Invalid read state");
        }
        Instant now = Instant.now();
        ReadStateEntity state = readStateRepository.findById(
                new com.uvya.apigateway.data.domain.ReadStateId(chatId, userId)).orElse(null);
        if (state == null) {
            state = new ReadStateEntity(chatId, userId, sequence, now);
        } else {
            state.advanceTo(sequence, now);
        }
        readStateRepository.save(state);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chatId", chatId.toString());
        payload.put("userId", userId.toString());
        payload.put("lastReadSequence", state.getLastReadSequence());
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(),
                EventType.MESSAGE_READ.value(), 1, now, traceId, idempotencyKey, payload),
                "CHAT", chatId));
        return state;
    }
}
