package com.uvya.apigateway.message.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.auth.service.AuditService;
import com.uvya.apigateway.auth.service.RequestContext;
import com.uvya.apigateway.chat.service.ChatAccessContext;
import com.uvya.apigateway.chat.service.ChatAction;
import com.uvya.apigateway.chat.service.ChatAuthorizationException;
import com.uvya.apigateway.chat.service.ChatAuthorizationPolicy;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.ChatMemberEntity;
import com.uvya.apigateway.data.domain.ChatMemberRole;
import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.domain.MessageStatus;
import com.uvya.apigateway.data.domain.MessageVersionEntity;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatRepository;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.data.repository.MessageVersionRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;
import com.uvya.apigateway.data.service.DataFoundationException;
import com.uvya.apigateway.data.service.MessageCreationCommand;
import com.uvya.apigateway.data.service.MessageService;
import com.uvya.apigateway.message.web.MessageHistoryResponse;
import com.uvya.apigateway.message.web.MessageResponse;
import com.uvya.apigateway.message.web.PatchMessageRequest;
import com.uvya.apigateway.message.web.SendMessageRequest;

@Service
public class MessageApplicationService {
    private static final int MAX_PAGE_SIZE = 100;

    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final MessageVersionRepository versionRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory eventFactory;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final ChatAuthorizationPolicy policy;
    private final MessageService messageService;

    public MessageApplicationService(ChatRepository chatRepository, MessageRepository messageRepository,
            MessageVersionRepository versionRepository, OutboxEventRepository outboxRepository,
            OutboxEventFactory eventFactory, ObjectMapper objectMapper, AuditService auditService,
            ChatAuthorizationPolicy policy, MessageService messageService) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.versionRepository = versionRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.policy = policy;
        this.messageService = messageService;
    }

    public MessageResponse send(ChatAccessContext context, UUID chatId, SendMessageRequest request,
            String idempotencyKey) {
        ChatEntity chat = chat(chatId);
        policy.authorize(context, chat, ChatAction.POST_MESSAGE);
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            throw new MessageValidationException("Attachments are not enabled for text messages yet");
        }
        String traceId = traceId(context);
        MessageCreationCommand command = new MessageCreationCommand(chatId, context.userId(), context.deviceId(),
                request.clientMessageId(), request.type().toLowerCase(java.util.Locale.ROOT), request.body(),
                request.replyToMessageId(), request.forwardedFromMessageId(), traceId, idempotencyKey);
        try {
            return MessageResponse.from(messageService.create(command));
        } catch (DataFoundationException exception) {
            throw mapDataException(exception);
        }
    }

    @Transactional(readOnly = true)
    public MessageHistoryResponse history(ChatAccessContext context, UUID chatId, String before, int size) {
        return history(context, chatId, before, null, size);
    }

    @Transactional(readOnly = true)
    public MessageHistoryResponse history(ChatAccessContext context, UUID chatId, String before, String after,
            int size) {
        ChatEntity chat = chat(chatId);
        policy.authorize(context, chat, ChatAction.VIEW);
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new MessageValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Long beforeCursor = parseCursor(before, "before");
        Long afterCursor = parseCursor(after, "after");
        if (beforeCursor != null && afterCursor != null) {
            throw new MessageValidationException("before and after cannot be used together");
        }
        if (afterCursor != null) {
            List<MessageEntity> oldestFirst = messageRepository
                    .findByChatIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(chatId, afterCursor,
                            PageRequest.of(0, size + 1));
            boolean hasMore = oldestFirst.size() > size;
            if (hasMore) {
                oldestFirst = new ArrayList<>(oldestFirst.subList(0, size));
            }
            String nextCursor = oldestFirst.isEmpty() ? after : Long.toString(
                    oldestFirst.get(oldestFirst.size() - 1).getSequenceNumber());
            List<MessageResponse> chronological = oldestFirst.stream().map(MessageResponse::from).toList();
            return new MessageHistoryResponse(chronological, nextCursor, hasMore);
        }

        List<MessageEntity> newestFirst = beforeCursor == null
                ? messageRepository.findByChatIdOrderBySequenceNumberDesc(chatId, PageRequest.of(0, size + 1))
                : messageRepository.findByChatIdAndSequenceNumberLessThanOrderBySequenceNumberDesc(chatId, beforeCursor,
                        PageRequest.of(0, size + 1));
        boolean hasMore = newestFirst.size() > size;
        if (hasMore) {
            newestFirst = new ArrayList<>(newestFirst.subList(0, size));
        }
        String nextCursor = hasMore && !newestFirst.isEmpty()
                ? Long.toString(newestFirst.get(newestFirst.size() - 1).getSequenceNumber()) : null;
        newestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(newestFirst);
        List<MessageResponse> chronological = newestFirst.stream().map(MessageResponse::from).toList();
        return new MessageHistoryResponse(chronological, nextCursor, hasMore);
    }

    @Transactional
    public MessageResponse edit(ChatAccessContext context, UUID chatId, UUID messageId,
            PatchMessageRequest request, String idempotencyKey) {
        ChatEntity chat = chat(chatId);
        ChatMemberEntity member = authorizePost(context, chat);
        MessageEntity message = lockMessage(messageId, chatId);
        if (!message.getSenderId().equals(context.userId())) {
            throw new MessageAuthorizationException("Only the sender can edit this message");
        }
        if (member.isBanned(Instant.now())) {
            throw new MessageAuthorizationException("User is banned from this chat");
        }
        if (message.getStatus() == MessageStatus.DELETED) {
            throw new MessageConflictException("Deleted messages cannot be edited");
        }
        if (request.expectedVersion() != null && request.expectedVersion() != message.getVersion()) {
            throw new MessageConflictException("Message version has changed");
        }
        Instant now = Instant.now();
        int nextVersion = message.getVersion() + 1;
        String type = request.type().toLowerCase(java.util.Locale.ROOT);
        message.edit(type, request.body(), now);
        versionRepository.save(new MessageVersionEntity(UUID.randomUUID(), messageId, nextVersion, type,
                request.body(), context.userId(), now));
        messageRepository.save(message);
        ObjectNode payload = messagePayload(message, context.userId());
        saveEvent(EventType.MESSAGE_EDITED, message, context, idempotencyKey, now, payload);
        auditService.record("MESSAGE_EDITED", context.userId(), context.deviceId(), null,
                new RequestContext(null, traceId(context), null));
        return MessageResponse.from(message);
    }

    @Transactional
    public MessageResponse delete(ChatAccessContext context, UUID chatId, UUID messageId, String idempotencyKey) {
        ChatEntity chat = chat(chatId);
        ChatMemberEntity member = authorizeView(context, chat);
        MessageEntity message = lockMessage(messageId, chatId);
        boolean ownerOrModerator = member.getRole() == ChatMemberRole.OWNER
                || member.getRole() == ChatMemberRole.ADMIN || member.getRole() == ChatMemberRole.MODERATOR;
        if (!message.getSenderId().equals(context.userId()) && !ownerOrModerator) {
            throw new MessageAuthorizationException("Message deletion permission required");
        }
        if (message.getStatus() == MessageStatus.DELETED) {
            return MessageResponse.from(message);
        }
        Instant now = Instant.now();
        message.delete(now);
        messageRepository.save(message);
        ObjectNode payload = messagePayload(message, context.userId());
        saveEvent(EventType.MESSAGE_DELETED, message, context, idempotencyKey, now, payload);
        auditService.record("MESSAGE_DELETED", context.userId(), context.deviceId(), null,
                new RequestContext(null, traceId(context), null));
        return MessageResponse.from(message);
    }

    private ChatMemberEntity authorizeView(ChatAccessContext context, ChatEntity chat) {
        try {
            return policy.authorize(context, chat, ChatAction.VIEW);
        } catch (ChatAuthorizationException exception) {
            throw new MessageAuthorizationException(exception.getMessage());
        }
    }

    private ChatMemberEntity authorizePost(ChatAccessContext context, ChatEntity chat) {
        try {
            return policy.authorize(context, chat, ChatAction.POST_MESSAGE);
        } catch (ChatAuthorizationException exception) {
            throw new MessageAuthorizationException(exception.getMessage());
        }
    }

    private MessageEntity lockMessage(UUID messageId, UUID chatId) {
        MessageEntity message = messageRepository.findByIdForUpdate(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found"));
        if (!chatId.equals(message.getChatId())) {
            throw new MessageNotFoundException("Message not found");
        }
        return message;
    }

    private ChatEntity chat(UUID chatId) {
        return chatRepository.findById(chatId).orElseThrow(() -> new MessageNotFoundException("Chat not found"));
    }

    private Long parseCursor(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long cursor = Long.parseLong(value);
            if (cursor < 1) {
                throw new NumberFormatException();
            }
            return cursor;
        } catch (NumberFormatException exception) {
            throw new MessageValidationException(name + " must be a positive sequence cursor");
        }
    }

    private String traceId(ChatAccessContext context) {
        return context.traceId() == null || context.traceId().isBlank() ? UUID.randomUUID().toString()
                : context.traceId();
    }

    private RuntimeException mapDataException(DataFoundationException exception) {
        String message = exception.getMessage();
        if (message != null && (message.contains("not found") || message.contains("not available"))) {
            return new MessageValidationException(message);
        }
        return new MessageConflictException(message == null ? "Message could not be persisted" : message);
    }

    private ObjectNode messagePayload(MessageEntity message, UUID actorId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", message.getMessageId().toString());
        payload.put("chatId", message.getChatId().toString());
        payload.put("actorId", actorId.toString());
        payload.put("sequence", message.getSequenceNumber());
        payload.put("messageType", message.getMessageType());
        if (message.getStatus() == MessageStatus.DELETED) {
            payload.putNull("body");
        } else {
            payload.put("body", message.getBody());
        }
        payload.put("version", message.getVersion());
        payload.put("status", message.getStatus().name());
        return payload;
    }

    private void saveEvent(EventType type, MessageEntity message, ChatAccessContext context,
            String idempotencyKey, Instant occurredAt, ObjectNode payload) {
        String eventKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? message.getMessageId() + ":" + type.value() : idempotencyKey;
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(), type.value(), 1,
                occurredAt, traceId(context), eventKey, payload), "MESSAGE", message.getMessageId()));
    }
}
