package com.uvya.apigateway.fanout;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.data.domain.ChatMemberEntity;
import com.uvya.apigateway.data.domain.DeliveryState;
import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.domain.UserInboxEntity;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;
import com.uvya.apigateway.data.repository.UserInboxRepository;
import com.uvya.apigateway.events.EventEnvelope;
import com.uvya.apigateway.events.config.FanoutProperties;
import com.uvya.apigateway.message.service.DeliveryStateCoordinator;
import com.uvya.apigateway.realtime.ActiveDevice;
import com.uvya.apigateway.realtime.ActiveDeviceDirectory;
import com.uvya.apigateway.realtime.RealtimeRoutePublisher;

@Service
public class MessageFanoutService {
    private final ChatMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory eventFactory;
    private final ObjectMapper objectMapper;
    private final FanoutProperties properties;
    private final ActiveDeviceDirectory deviceDirectory;
    private final RealtimeRoutePublisher routePublisher;
    private final DeliveryStateCoordinator deliveryState;
    private final UserInboxRepository inboxRepository;

    public MessageFanoutService(ChatMemberRepository memberRepository, MessageRepository messageRepository,
            OutboxEventRepository outboxRepository, OutboxEventFactory eventFactory, ObjectMapper objectMapper,
            FanoutProperties properties, ActiveDeviceDirectory deviceDirectory,
            RealtimeRoutePublisher routePublisher, DeliveryStateCoordinator deliveryState,
            UserInboxRepository inboxRepository) {
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.deviceDirectory = deviceDirectory;
        this.routePublisher = routePublisher;
        this.deliveryState = deliveryState;
        this.inboxRepository = inboxRepository;
    }

    public void handleCreated(EventEnvelope event) {
        JsonNode payload = event.payload();
        UUID messageId = requiredUuid(payload, "messageId");
        UUID chatId = requiredUuid(payload, "chatId");
        UUID senderId = requiredUuid(payload, "senderId");
        UUID senderDeviceId = requiredUuid(payload, "senderDeviceId");
        long sequence = requiredPositiveLong(payload, "sequence");
        String mode = payload.path("fanoutMode").asText("READ");
        Set<UUID> recipients = recipients(payload, chatId, mode);
        for (UUID recipientId : recipients) {
            deliverOrQueue(event, payload, recipientId, senderId, senderDeviceId, messageId, chatId, sequence,
                    mode);
        }
    }

    public void handleDelivered(EventEnvelope event) {
        UUID messageId = requiredUuid(event.payload(), "messageId");
        UUID chatId = requiredUuid(event.payload(), "chatId");
        MessageEntity message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalStateException("Message for delivery event was not found"));
        if (!chatId.equals(message.getChatId())) {
            throw new IllegalStateException("Delivery event references another chat");
        }
        ObjectNode outbound = objectMapper.createObjectNode();
        outbound.put("type", EventType.MESSAGE_DELIVERED.value());
        outbound.put("messageId", messageId.toString());
        outbound.put("chatId", chatId.toString());
        outbound.put("eventId", event.eventId().toString());
        outbound.put("correlationId", event.correlationId());
        outbound.set("payload", event.payload().deepCopy());
        for (ActiveDevice device : deviceDirectory.activeDevices(message.getSenderId())) {
            routePublisher.publishToDevice(device, outbound.deepCopy());
        }
    }

    public void handleInteraction(EventEnvelope event) {
        JsonNode payload = event.payload();
        UUID chatId = requiredUuid(payload, "chatId");
        if (!payload.isObject()) {
            throw new IllegalArgumentException("interaction payload must be an object");
        }
        ObjectNode outbound = objectMapper.createObjectNode();
        outbound.put("type", event.eventType());
        outbound.put("eventId", event.eventId().toString());
        outbound.put("correlationId", event.correlationId());
        outbound.put("messageId", payload.path("messageId").asText());
        outbound.put("chatId", chatId.toString());
        outbound.set("payload", payload.deepCopy());
        for (ChatMemberEntity member : memberRepository.findByIdChatIdAndLeftAtIsNullOrderByJoinedAtAsc(chatId)) {
            for (ActiveDevice device : deviceDirectory.activeDevices(member.getUserId())) {
                routePublisher.publishToDevice(device, outbound.deepCopy());
            }
        }
    }

    public void retryPending() {
        Instant before = Instant.now().minus(properties.getRetryAfter());
        List<UserInboxEntity> pending = pendingDeliveries(before);
        for (UserInboxEntity inbox : pending) {
            retry(inbox);
        }
    }

    private List<UserInboxEntity> pendingDeliveries(Instant before) {
        return inboxRepository.findTop100ByDeliveryStateAndLastDeliveryAttemptAtBeforeOrderByLastDeliveryAttemptAtAsc(
                DeliveryState.PENDING, before);
    }

    private void retry(UserInboxEntity inbox) {
        MessageEntity message = messageRepository.findById(inbox.getMessageId()).orElse(null);
        if (message == null) {
            deliveryState.markFailed(inbox.getUserId(), inbox.getMessageId());
            return;
        }
        List<ActiveDevice> devices = deliverableDevices(inbox.getUserId(), message.getSenderId(),
                message.getSenderDeviceId());
        if (devices.isEmpty()) {
            if (inbox.getDeliveryAttempts() >= properties.getMaxDeliveryAttempts()) {
                deliveryState.markFailed(inbox.getUserId(), inbox.getMessageId());
            }
            return;
        }
        if (!deliveryState.markPending(inbox.getUserId(), message.getMessageId(), message.getChatId(),
                message.getSequenceNumber(), message.getCreatedAt(), properties.getMaxDeliveryAttempts())) {
            return;
        }
        try {
            ObjectNode outbound = messageNew(message, null);
            for (ActiveDevice device : devices) {
                routePublisher.publishToDevice(device, outbound.deepCopy());
            }
        } catch (RuntimeException exception) {
            deliveryState.recordAttemptFailure(inbox.getUserId(), inbox.getMessageId(),
                    properties.getMaxDeliveryAttempts());
        }
    }

    private void deliverOrQueue(EventEnvelope event, JsonNode payload, UUID recipientId, UUID senderId,
            UUID senderDeviceId, UUID messageId, UUID chatId, long sequence, String mode) {
        List<ActiveDevice> devices = deliverableDevices(recipientId, senderId, senderDeviceId);
        if (devices.isEmpty()) {
            if ("WRITE".equalsIgnoreCase(mode) && !recipientId.equals(senderId)) {
                queueNotification(event, payload, recipientId, messageId, chatId);
            }
            return;
        }
        if (!deliveryState.markPending(recipientId, messageId, chatId, sequence,
                event.occurredAt(), properties.getMaxDeliveryAttempts())) {
            return;
        }
        try {
            ObjectNode outbound = messageNewPayload(event, payload, messageId, chatId, sequence);
            for (ActiveDevice device : devices) {
                routePublisher.publishToDevice(device, outbound.deepCopy());
            }
        } catch (RuntimeException exception) {
            deliveryState.recordAttemptFailure(recipientId, messageId, properties.getMaxDeliveryAttempts());
            throw exception;
        }
    }

    private List<ActiveDevice> deliverableDevices(UUID userId, UUID senderId, UUID senderDeviceId) {
        return deviceDirectory.activeDevices(userId).stream()
                .filter(device -> !userId.equals(senderId) || !device.deviceId().equals(senderDeviceId))
                .toList();
    }

    private Set<UUID> recipients(JsonNode payload, UUID chatId, String mode) {
        Set<UUID> recipients = new LinkedHashSet<>();
        JsonNode recipientNode = payload.get("recipientUserIds");
        if ("WRITE".equalsIgnoreCase(mode) && recipientNode != null && recipientNode.isArray()) {
            for (JsonNode value : recipientNode) {
                recipients.add(parseUuid(value.asText(null), "recipientUserId"));
            }
            return recipients;
        }
        for (ChatMemberEntity member : memberRepository.findByIdChatIdAndLeftAtIsNullOrderByJoinedAtAsc(chatId)) {
            recipients.add(member.getUserId());
        }
        return recipients;
    }

    private void queueNotification(EventEnvelope event, JsonNode messagePayload, UUID userId, UUID messageId,
            UUID chatId) {
        String idempotencyKey = "message-notification:" + messageId + ":" + userId;
        if (outboxRepository.existsByEventTypeAndIdempotencyKey(EventType.NOTIFICATION_REQUESTED.value(),
                idempotencyKey)) {
            return;
        }
        ObjectNode data = objectMapper.createObjectNode();
        data.put("messageId", messageId.toString());
        data.put("chatId", chatId.toString());
        data.put("senderId", messagePayload.path("senderId").asText());
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("notificationId", UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8))
                .toString());
        notification.put("userId", userId.toString());
        notification.put("template", "message.created");
        notification.set("data", data);
        Instant now = Instant.now();
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(),
                EventType.NOTIFICATION_REQUESTED.value(), 1, now, event.traceId(), event.correlationId(),
                idempotencyKey, notification), "MESSAGE", messageId));
    }

    private ObjectNode messageNewPayload(EventEnvelope event, JsonNode payload, UUID messageId, UUID chatId,
            long sequence) {
        ObjectNode outbound = messageNew(event, payload);
        outbound.put("messageId", messageId.toString());
        outbound.put("chatId", chatId.toString());
        outbound.put("sequence", sequence);
        return outbound;
    }

    private ObjectNode messageNew(MessageEntity message, EventEnvelope event) {
        ObjectNode outbound = objectMapper.createObjectNode();
        outbound.put("type", "message.new");
        outbound.put("messageId", message.getMessageId().toString());
        outbound.put("chatId", message.getChatId().toString());
        outbound.put("sequence", message.getSequenceNumber());
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
        boolean replyToDeleted = message.getReplyToMessageId() != null
                && messageRepository.findById(message.getReplyToMessageId())
                        .map(reply -> reply.getStatus() == com.uvya.apigateway.data.domain.MessageStatus.DELETED)
                        .orElse(false);
        payload.put("replyToDeleted", replyToDeleted);
        payload.put("version", message.getVersion());
        payload.put("status", message.getStatus().name());
        putNullable(payload, "threadRootMessageId", message.getThreadRootMessageId());
        putNullable(payload, "forwardedFromChatId", message.getForwardedFromChatId());
        putNullable(payload, "forwardedFromSenderId", message.getForwardedFromSenderId());
        if (message.getForwardedFromCreatedAt() == null) {
            payload.putNull("forwardedFromCreatedAt");
        } else {
            payload.put("forwardedFromCreatedAt", message.getForwardedFromCreatedAt().toString());
        }
        outbound.set("payload", payload);
        if (event != null) {
            outbound.put("eventId", event.eventId().toString());
            outbound.put("correlationId", event.correlationId());
        }
        return outbound;
    }

    private ObjectNode messageNew(EventEnvelope event, JsonNode payload) {
        UUID messageId = requiredUuid(payload, "messageId");
        UUID chatId = requiredUuid(payload, "chatId");
        ObjectNode outbound = objectMapper.createObjectNode();
        outbound.put("type", "message.new");
        outbound.put("messageId", messageId.toString());
        outbound.put("chatId", chatId.toString());
        outbound.put("sequence", requiredPositiveLong(payload, "sequence"));
        outbound.put("eventId", event.eventId().toString());
        outbound.put("correlationId", event.correlationId());
        if (!payload.isObject()) {
            throw new IllegalArgumentException("message.created payload must be an object");
        }
        ObjectNode clientPayload = (ObjectNode) payload.deepCopy();
        clientPayload.remove("fanoutMode");
        clientPayload.remove("recipientUserIds");
        outbound.set("payload", clientPayload);
        return outbound;
    }

    private UUID requiredUuid(JsonNode payload, String field) {
        return parseUuid(payload.path(field).asText(null), field);
    }

    private UUID parseUuid(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("message.created payload requires " + field);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("message.created payload has an invalid " + field, exception);
        }
    }

    private long requiredPositiveLong(JsonNode payload, String field) {
        if (!payload.has(field) || !payload.get(field).canConvertToLong() || payload.get(field).asLong() < 1) {
            throw new IllegalArgumentException("message.created payload requires a positive " + field);
        }
        return payload.get(field).asLong();
    }

    private void putNullable(ObjectNode payload, String field, UUID value) {
        if (value == null) {
            payload.putNull(field);
        } else {
            payload.put(field, value.toString());
        }
    }
}
