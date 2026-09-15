package com.uvya.apigateway.message.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.chat.service.ChatAccessContext;
import com.uvya.apigateway.chat.service.ChatAction;
import com.uvya.apigateway.chat.service.ChatAuthorizationException;
import com.uvya.apigateway.chat.service.ChatAuthorizationPolicy;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.DeliveryState;
import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.domain.UserInboxEntity;
import com.uvya.apigateway.data.domain.UserInboxId;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.EventType;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.ChatRepository;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;
import com.uvya.apigateway.data.repository.UserInboxRepository;

@Service
public class MessageDeliveryService {
    private final ChatRepository chatRepository;
    private final ChatMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final UserInboxRepository inboxRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory eventFactory;
    private final ObjectMapper objectMapper;
    private final ChatAuthorizationPolicy policy;

    public MessageDeliveryService(ChatRepository chatRepository, ChatMemberRepository memberRepository,
            MessageRepository messageRepository, UserInboxRepository inboxRepository,
            OutboxEventRepository outboxRepository, OutboxEventFactory eventFactory,
            ObjectMapper objectMapper, ChatAuthorizationPolicy policy) {
        this.chatRepository = chatRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.inboxRepository = inboxRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.objectMapper = objectMapper;
        this.policy = policy;
    }

    @Transactional
    public boolean acknowledge(ChatAccessContext context, UUID chatId, UUID messageId) {
        ChatEntity chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new MessageNotFoundException("Chat not found"));
        try {
            policy.authorize(context, chat, ChatAction.VIEW);
        } catch (ChatAuthorizationException exception) {
            throw new MessageAuthorizationException(exception.getMessage());
        }
        MessageEntity message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found"));
        if (!chatId.equals(message.getChatId())) {
            throw new MessageNotFoundException("Message not found");
        }
        if (message.getSenderId().equals(context.userId())
                && message.getSenderDeviceId().equals(context.deviceId())) {
            throw new MessageAuthorizationException("The sending device cannot acknowledge its own persistence");
        }

        UserInboxId inboxId = new UserInboxId(context.userId(), messageId);
        UserInboxEntity inbox = inboxRepository.findByIdForUpdate(inboxId).orElse(null);
        if (inbox == null) {
            if (!memberRepository.isActiveMember(chatId, context.userId())) {
                throw new MessageAuthorizationException("User is not an active chat member");
            }
            inbox = new UserInboxEntity(context.userId(), messageId, chatId, message.getSequenceNumber(),
                    message.getCreatedAt());
        }
        if (inbox.getDeliveryState() == DeliveryState.DELIVERED
                || inbox.getDeliveryState() == DeliveryState.READ) {
            return false;
        }

        Instant now = Instant.now();
        inbox.markDelivered(now);
        inboxRepository.save(inbox);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", messageId.toString());
        payload.put("chatId", chatId.toString());
        payload.put("userId", context.userId().toString());
        payload.put("deliveredAt", now.toString());
        String key = "message-delivered:" + messageId + ":" + context.userId();
        outboxRepository.save(eventFactory.toEntity(new DomainEvent(UUID.randomUUID(),
                EventType.MESSAGE_DELIVERED.value(), 1, now, trace(context), key, payload),
                "MESSAGE", messageId));
        return true;
    }

    private String trace(ChatAccessContext context) {
        return context.traceId() == null || context.traceId().isBlank()
                ? UUID.randomUUID().toString() : context.traceId();
    }
}
