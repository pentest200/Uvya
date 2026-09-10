package com.uvya.apigateway.data.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.auth.service.AuditService;
import com.uvya.apigateway.auth.service.RequestContext;
import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.domain.MessageVersionEntity;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.data.repository.MessageVersionRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;

@Service
public class MessageVersionService {
    private final MessageRepository messageRepository;
    private final MessageVersionRepository versionRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory eventFactory;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public MessageVersionService(MessageRepository messageRepository, MessageVersionRepository versionRepository,
            OutboxEventRepository outboxRepository, OutboxEventFactory eventFactory,
            ObjectMapper objectMapper, AuditService auditService) {
        this.messageRepository = messageRepository;
        this.versionRepository = versionRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional
    public MessageEntity edit(UUID editorId, UUID messageId, String messageType, String body,
            String traceId, String idempotencyKey) {
        MessageEntity message = messageRepository.findById(messageId)
                .orElseThrow(() -> new DataFoundationException("Message not found"));
        if (!editorId.equals(message.getSenderId())) {
            throw new DataFoundationException("Only the sender can edit this message");
        }
        Instant now = Instant.now();
        int nextVersion = message.getVersion() + 1;
        message.edit(messageType, body == null ? "" : body, now);
        versionRepository.save(new MessageVersionEntity(UUID.randomUUID(), messageId, nextVersion,
                messageType, message.getBody(), editorId, now));
        messageRepository.save(message);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", messageId.toString());
        payload.put("chatId", message.getChatId().toString());
        payload.put("editorId", editorId.toString());
        payload.put("version", message.getVersion());
        payload.put("messageType", messageType);
        payload.put("body", message.getBody());
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(),
                EventType.MESSAGE_EDITED.value(), 1, now, traceId, idempotencyKey, payload),
                "MESSAGE", messageId));
        auditService.record("MESSAGE_EDITED", editorId, message.getSenderDeviceId(), null,
                new RequestContext(null, traceId, null));
        return message;
    }
}
