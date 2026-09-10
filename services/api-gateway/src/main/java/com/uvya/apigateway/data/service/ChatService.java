package com.uvya.apigateway.data.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.auth.service.AuditService;
import com.uvya.apigateway.auth.service.RequestContext;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.ChatMemberEntity;
import com.uvya.apigateway.data.domain.ChatMemberRole;
import com.uvya.apigateway.data.domain.ChatType;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.ChatRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;

@Service
public class ChatService {
    private final ChatRepository chatRepository;
    private final ChatMemberRepository memberRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory eventFactory;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public ChatService(ChatRepository chatRepository, ChatMemberRepository memberRepository,
            OutboxEventRepository outboxRepository, OutboxEventFactory eventFactory,
            ObjectMapper objectMapper, AuditService auditService) {
        this.chatRepository = chatRepository;
        this.memberRepository = memberRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional
    public ChatEntity createChat(UUID creatorId, ChatType chatType, String title,
            String traceId, String idempotencyKey) {
        requireId(creatorId, "creatorId");
        requireText(traceId, "traceId");
        requireText(idempotencyKey, "idempotencyKey");
        Instant now = Instant.now();
        ChatEntity chat = new ChatEntity(UUID.randomUUID(), chatType, title, creatorId, now);
        chatRepository.save(chat);
        memberRepository.save(new ChatMemberEntity(chat.getId(), creatorId, ChatMemberRole.OWNER, now));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chatId", chat.getId().toString());
        payload.put("createdBy", creatorId.toString());
        payload.put("chatType", chatType.name());
        if (title == null) {
            payload.putNull("title");
        } else {
            payload.put("title", title);
        }
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(),
                EventType.CHAT_CREATED.value(), 1, now, traceId, idempotencyKey, payload),
                "CHAT", chat.getId()));
        auditService.record("CHAT_CREATED", creatorId, null, null,
                new RequestContext(null, traceId, null));
        return chat;
    }

    @Transactional
    public void addMember(UUID actorId, UUID chatId, UUID userId, ChatMemberRole role,
            String traceId, String idempotencyKey) {
        ChatEntity chat = lockedChat(chatId);
        requireActiveMember(chat.getId(), actorId);
        requireText(traceId, "traceId");
        requireText(idempotencyKey, "idempotencyKey");
        ChatMemberEntity member = memberRepository.findById(
                new com.uvya.apigateway.data.domain.ChatMemberId(chatId, userId)).orElse(null);
        Instant now = Instant.now();
        if (member == null) {
            memberRepository.save(new ChatMemberEntity(chatId, userId, role, now));
        } else if (member.getLeftAt() != null) {
            memberRepository.delete(member);
            memberRepository.save(new ChatMemberEntity(chatId, userId, role, now));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chatId", chatId.toString());
        payload.put("userId", userId.toString());
        payload.put("role", role.name());
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(),
                EventType.GROUP_MEMBER_ADDED.value(), 1, now, traceId, idempotencyKey, payload),
                "CHAT", chat.getId()));
    }

    @Transactional
    public void removeMember(UUID actorId, UUID chatId, UUID userId, String traceId,
            String idempotencyKey) {
        ChatEntity chat = lockedChat(chatId);
        requireActiveMember(chat.getId(), actorId);
        requireText(traceId, "traceId");
        requireText(idempotencyKey, "idempotencyKey");
        ChatMemberEntity member = memberRepository.findById(
                new com.uvya.apigateway.data.domain.ChatMemberId(chatId, userId))
                .orElseThrow(() -> new DataFoundationException("Chat member not found"));
        member.leave(Instant.now());
        memberRepository.save(member);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chatId", chatId.toString());
        payload.put("userId", userId.toString());
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(),
                EventType.GROUP_MEMBER_REMOVED.value(), 1, Instant.now(), traceId, idempotencyKey, payload),
                "CHAT", chat.getId()));
    }

    private ChatEntity lockedChat(UUID chatId) {
        return chatRepository.findByIdForUpdate(chatId)
                .orElseThrow(() -> new DataFoundationException("Chat not found"));
    }

    private void requireActiveMember(UUID chatId, UUID userId) {
        if (!memberRepository.isActiveMember(chatId, userId)) {
            throw new DataFoundationException("User is not an active chat member");
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
