package com.uvya.apigateway.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.uvya.apigateway.auth.domain.UserEntity;
import com.uvya.apigateway.auth.domain.UserStatus;
import com.uvya.apigateway.auth.repository.UserRepository;
import com.uvya.apigateway.auth.service.AuditService;
import com.uvya.apigateway.auth.service.RequestContext;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.ChatMemberEntity;
import com.uvya.apigateway.data.domain.ChatMemberRole;
import com.uvya.apigateway.data.domain.ChatPinnedMessageEntity;
import com.uvya.apigateway.data.domain.ChatSettingsEntity;
import com.uvya.apigateway.data.domain.ChatType;
import com.uvya.apigateway.data.domain.IdempotencyKeyEntity;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.ChatPinnedMessageRepository;
import com.uvya.apigateway.data.repository.ChatRepository;
import com.uvya.apigateway.data.repository.ChatSettingsRepository;
import com.uvya.apigateway.data.repository.IdempotencyKeyRepository;
import com.uvya.apigateway.chat.web.AddChatMemberRequest;
import com.uvya.apigateway.chat.web.ChatMemberResponse;
import com.uvya.apigateway.chat.web.ChatResponse;
import com.uvya.apigateway.chat.web.ChatSettingsRequest;
import com.uvya.apigateway.chat.web.ChatSettingsResponse;
import com.uvya.apigateway.chat.web.CreateChatRequest;
import com.uvya.apigateway.chat.web.PatchChatRequest;

@Service
public class ChatApplicationService {
    private static final int MAX_PAGE_SIZE = 100;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository memberRepository;
    private final ChatSettingsRepository settingsRepository;
    private final ChatPinnedMessageRepository pinnedRepository;
    private final IdempotencyKeyRepository idempotencyRepository;
    private final UserRepository userRepository;
    private final ChatAuthorizationPolicy policy;
    private final OutboxEventFactory eventFactory;
    private final com.uvya.apigateway.data.repository.OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public ChatApplicationService(ChatRepository chatRepository, ChatMemberRepository memberRepository,
            ChatSettingsRepository settingsRepository, ChatPinnedMessageRepository pinnedRepository,
            IdempotencyKeyRepository idempotencyRepository, UserRepository userRepository,
            ChatAuthorizationPolicy policy, OutboxEventFactory eventFactory,
            com.uvya.apigateway.data.repository.OutboxEventRepository outboxRepository,
            ObjectMapper objectMapper, AuditService auditService) {
        this.chatRepository = chatRepository;
        this.memberRepository = memberRepository;
        this.settingsRepository = settingsRepository;
        this.pinnedRepository = pinnedRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.userRepository = userRepository;
        this.policy = policy;
        this.eventFactory = eventFactory;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional
    public ChatResponse create(ChatAccessContext context, CreateChatRequest request, String idempotencyKey) {
        policy.validateIdentity(context);
        validateCreateRequest(context, request);
        String safeIdempotencyKey = requireKey(idempotencyKey);
        String requestHash = hashRequest(request);
        IdempotencyKeyEntity previous = idempotencyRepository.findByUserIdAndIdempotencyKey(context.userId(),
                safeIdempotencyKey).orElse(null);
        if (previous != null) {
            if (!previous.getRequestHash().equals(requestHash) || previous.getResourceId() == null) {
                throw new ChatConflictException("Idempotency key was used for another request");
            }
            return get(context, previous.getResourceId());
        }

        Instant now = Instant.now();
        ChatEntity chat = new ChatEntity(UUID.randomUUID(), request.chatType(), request.title(), context.userId(), now);
        chatRepository.save(chat);
        boolean defaultPosting = request.chatType() != ChatType.CHANNEL;
        settingsRepository.save(new ChatSettingsEntity(chat.getId(), setting(request.settings(),
                defaultPosting, ChatSettingsRequest::memberPostingEnabled), setting(request.settings(), false,
                ChatSettingsRequest::discoverable), setting(request.settings(), 0, ChatSettingsRequest::slowModeSeconds),
                now));
        memberRepository.save(new ChatMemberEntity(chat.getId(), context.userId(), ChatMemberRole.OWNER, now));
        addInitialMembers(chat, context.userId(), request.memberIds(), now);
        emitChatCreated(chat, context, safeIdempotencyKey, now);
        idempotencyRepository.save(new IdempotencyKeyEntity(UUID.randomUUID(), context.userId(), safeIdempotencyKey,
                requestHash, "CHAT", chat.getId(), now, now.plus(24, ChronoUnit.HOURS)));
        auditService.record("CHAT_CREATED", context.userId(), context.deviceId(), context.sessionId(), auditContext(context));
        return response(chat, context.userId());
    }

    @Transactional(readOnly = true)
    public Page<ChatResponse> list(ChatAccessContext context, int page, int size) {
        policy.validateIdentity(context);
        PageRequest pageable = pageRequest(page, size);
        return chatRepository.findActiveChatsForUser(context.userId(), pageable)
                .map(chat -> response(chat, context.userId()));
    }

    @Transactional(readOnly = true)
    public ChatResponse get(ChatAccessContext context, UUID chatId) {
        ChatEntity chat = chat(chatId);
        policy.authorize(context, chat, ChatAction.VIEW);
        return response(chat, context.userId());
    }

    @Transactional
    public ChatResponse update(ChatAccessContext context, UUID chatId, PatchChatRequest request) {
        ChatEntity chat = lockedChat(chatId);
        policy.authorize(context, chat, ChatAction.UPDATE);
        Instant now = Instant.now();
        if (request.title() != null) {
            chat.updateTitle(request.title().trim(), now);
            chatRepository.save(chat);
        }
        if (request.settings() != null) {
            ChatSettingsEntity settings = settings(chat, now);
            applySettings(settings, request.settings(), now);
            settingsRepository.save(settings);
        }
        auditService.record("CHAT_UPDATED", context.userId(), context.deviceId(), context.sessionId(), auditContext(context));
        return response(chat, context.userId());
    }

    @Transactional
    public void addMember(ChatAccessContext context, UUID chatId, AddChatMemberRequest request, String idempotencyKey) {
        ChatEntity chat = lockedChat(chatId);
        policy.authorizeMemberRole(context, chat, request.role());
        if (request.userId().equals(context.userId())) {
            throw new ChatConflictException("User is already the acting member");
        }
        UserEntity target = userRepository.findById(request.userId()).orElseThrow(
                () -> new ChatValidationException("Target user does not exist"));
        if (target.getStatus() != UserStatus.ACTIVE) {
            throw new ChatValidationException("Target user is not active");
        }
        ChatMemberEntity existing = memberRepository.findByIdForUpdate(chatId, request.userId()).orElse(null);
        if (existing != null && existing.getLeftAt() == null) {
            throw new ChatConflictException("User is already a chat member");
        }
        if (existing != null && existing.isBanned(Instant.now())) {
            throw new ChatAuthorizationException("Banned users cannot be added");
        }
        Instant now = Instant.now();
        if (existing != null) {
            memberRepository.delete(existing);
            memberRepository.flush();
        }
        memberRepository.save(new ChatMemberEntity(chatId, request.userId(), request.role(), now));
        emitMemberEvent(EventType.GROUP_MEMBER_ADDED, chat, context, request.userId(), request.role().name(),
                requireKey(idempotencyKey), now);
        auditService.record("CHAT_MEMBER_ADDED", context.userId(), context.deviceId(), context.sessionId(), auditContext(context));
    }

    @Transactional
    public void removeMember(ChatAccessContext context, UUID chatId, UUID userId, String idempotencyKey) {
        ChatEntity chat = lockedChat(chatId);
        ChatMemberEntity target = memberRepository.findByIdForUpdate(chatId, userId)
                .orElseThrow(() -> new ChatValidationException("Chat member not found"));
        policy.authorizeMemberRemoval(context, chat, target);
        if (target.getLeftAt() != null) {
            throw new ChatConflictException("User is not an active chat member");
        }
        Instant now = Instant.now();
        target.leave(now);
        memberRepository.save(target);
        emitMemberEvent(EventType.GROUP_MEMBER_REMOVED, chat, context, userId, null, requireKey(idempotencyKey), now);
        auditService.record("CHAT_MEMBER_REMOVED", context.userId(), context.deviceId(), context.sessionId(), auditContext(context));
    }

    @Transactional
    public void leave(ChatAccessContext context, UUID chatId, String idempotencyKey) {
        ChatEntity chat = lockedChat(chatId);
        ChatMemberEntity member = policy.authorize(context, chat, ChatAction.LEAVE);
        Instant now = Instant.now();
        member.leave(now);
        memberRepository.save(member);
        emitMemberEvent(EventType.GROUP_MEMBER_REMOVED, chat, context, context.userId(), null,
                requireKey(idempotencyKey), now);
        auditService.record("CHAT_LEFT", context.userId(), context.deviceId(), context.sessionId(), auditContext(context));
    }

    @Transactional(readOnly = true)
    public Page<ChatMemberResponse> members(ChatAccessContext context, UUID chatId, int page, int size) {
        ChatEntity chat = chat(chatId);
        policy.authorize(context, chat, ChatAction.VIEW_MEMBERS);
        return memberRepository.findByIdChatIdAndLeftAtIsNull(chatId, pageRequest(page, size))
                .map(member -> new ChatMemberResponse(member.getUserId(), member.getRole(), member.getJoinedAt(),
                        member.getMutedUntil(), member.getArchivedAt()));
    }

    private void validateCreateRequest(ChatAccessContext context, CreateChatRequest request) {
        if (request == null || request.chatType() == null) {
            throw new ChatValidationException("Chat type is required");
        }
        List<UUID> memberIds = request.memberIds() == null ? List.of() : request.memberIds();
        if (memberIds.contains(context.userId())) {
            throw new ChatValidationException("Creator must not be included in memberIds");
        }
        if (request.chatType() == ChatType.DIRECT && memberIds.size() != 1) {
            throw new ChatValidationException("Direct chats require exactly one other member");
        }
        if (request.chatType() == ChatType.DIRECT && request.settings() != null) {
            throw new ChatValidationException("Direct chat settings are not configurable at creation");
        }
        if (request.settings() != null && request.settings().slowModeSeconds() != null
                && request.settings().slowModeSeconds() < 0) {
            throw new ChatValidationException("slowModeSeconds cannot be negative");
        }
    }

    private void addInitialMembers(ChatEntity chat, UUID creatorId, List<UUID> memberIds, Instant now) {
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }
        List<UserEntity> targets = userRepository.findAllById(memberIds);
        if (targets.size() != memberIds.size() || targets.stream().anyMatch(user -> user.getStatus() != UserStatus.ACTIVE)) {
            throw new ChatValidationException("Every initial member must be an active user");
        }
        targets.forEach(user -> memberRepository.save(new ChatMemberEntity(chat.getId(), user.getId(),
                ChatMemberRole.MEMBER, now)));
    }

    private ChatResponse response(ChatEntity chat, UUID viewerId) {
        ChatMemberEntity viewer = memberRepository.findById(new com.uvya.apigateway.data.domain.ChatMemberId(
                chat.getId(), viewerId)).orElseThrow(() -> new ChatAuthorizationException("User is not a chat member"));
        ChatSettingsEntity settings = settingsRepository.findByChatId(chat.getId()).orElseGet(() ->
                new ChatSettingsEntity(chat.getId(), chat.getChatType() != ChatType.CHANNEL, false, 0,
                        chat.getCreatedAt()));
        List<UUID> pinned = pinnedRepository.findTop50ByChatIdOrderByPinnedAtDesc(chat.getId()).stream()
                .map(ChatPinnedMessageEntity::getMessageId).toList();
        return new ChatResponse(chat.getId(), chat.getChatType(), chat.getTitle(), chat.getCreatedBy(), chat.getCreatedAt(),
                chat.getUpdatedAt(), viewer.getRole(), memberRepository.countByIdChatIdAndLeftAtIsNull(chat.getId()),
                new ChatSettingsResponse(settings.isMemberPostingEnabled(), settings.isDiscoverable(),
                        settings.getSlowModeSeconds()), pinned);
    }

    private ChatSettingsEntity settings(ChatEntity chat, Instant now) {
        return settingsRepository.findByChatId(chat.getId()).orElseGet(() -> new ChatSettingsEntity(chat.getId(),
                chat.getChatType() != ChatType.CHANNEL, false, 0, now));
    }

    private void applySettings(ChatSettingsEntity entity, ChatSettingsRequest request, Instant now) {
        entity.update(request.memberPostingEnabled(), request.discoverable(), request.slowModeSeconds(), now);
    }

    private <T> T setting(ChatSettingsRequest request, T defaultValue, java.util.function.Function<ChatSettingsRequest, T> getter) {
        return request == null || getter.apply(request) == null ? defaultValue : getter.apply(request);
    }

    private ChatEntity chat(UUID chatId) {
        return chatRepository.findById(chatId).orElseThrow(ChatNotFoundException::new);
    }

    private ChatEntity lockedChat(UUID chatId) {
        return chatRepository.findByIdForUpdate(chatId).orElseThrow(ChatNotFoundException::new);
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ChatValidationException("Invalid pagination");
        }
        return PageRequest.of(page, size);
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new ChatValidationException("Idempotency-Key is required");
        }
        return key;
    }

    private String hashRequest(CreateChatRequest request) {
        String material = request.chatType() + "|" + request.title() + "|" + request.memberIds() + "|" + request.settings();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void emitChatCreated(ChatEntity chat, ChatAccessContext context, String key, Instant now) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chatId", chat.getId().toString());
        payload.put("createdBy", chat.getCreatedBy().toString());
        payload.put("chatType", chat.getChatType().name());
        payload.put("title", chat.getTitle());
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(), EventType.CHAT_CREATED.value(), 1,
                now, trace(context), key, payload), "CHAT", chat.getId()));
    }

    private void emitMemberEvent(EventType type, ChatEntity chat, ChatAccessContext context, UUID userId,
            String role, String key, Instant now) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chatId", chat.getId().toString());
        payload.put("userId", userId.toString());
        if (role == null) {
            payload.putNull("role");
        } else {
            payload.put("role", role);
        }
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(), type.value(), 1, now,
                trace(context), key, payload), "CHAT", chat.getId()));
    }

    private String trace(ChatAccessContext context) {
        return context.traceId() == null || context.traceId().isBlank() ? UUID.randomUUID().toString() : context.traceId();
    }

    private RequestContext auditContext(ChatAccessContext context) {
        return new RequestContext(null, trace(context), null);
    }
}
