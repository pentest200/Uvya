package com.uvya.apigateway.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.chat.service.ChatAccessContext;
import com.uvya.apigateway.chat.service.ChatAction;
import com.uvya.apigateway.chat.service.ChatAuthorizationPolicy;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.ChatType;
import com.uvya.apigateway.data.domain.DeliveryState;
import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.domain.OutboxEventEntity;
import com.uvya.apigateway.data.domain.UserInboxEntity;
import com.uvya.apigateway.data.event.DomainEvent;
import com.uvya.apigateway.data.event.OutboxEventFactory;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.ChatRepository;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;
import com.uvya.apigateway.data.repository.UserInboxRepository;
import com.uvya.apigateway.events.EventEnvelope;
import com.uvya.apigateway.events.config.FanoutProperties;
import com.uvya.apigateway.message.service.DeliveryStateCoordinator;
import com.uvya.apigateway.message.service.MessageDeliveryService;
import com.uvya.apigateway.realtime.ActiveDevice;
import com.uvya.apigateway.realtime.ActiveDeviceDirectory;
import com.uvya.apigateway.realtime.RealtimeRoutePublisher;

@ExtendWith(MockitoExtension.class)
class MessageFanoutServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID chatId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID senderDeviceId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();

    @Mock private ChatMemberRepository memberRepository;
    @Mock private ChatRepository chatRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private OutboxEventRepository outboxRepository;
    @Mock private OutboxEventFactory eventFactory;
    @Mock private FanoutProperties properties;
    @Mock private ActiveDeviceDirectory deviceDirectory;
    @Mock private RealtimeRoutePublisher routePublisher;
    @Mock private DeliveryStateCoordinator deliveryState;
    @Mock private UserInboxRepository inboxRepository;
    @Mock private ChatAuthorizationPolicy authorizationPolicy;

    private MessageFanoutService fanoutService;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getMaxDeliveryAttempts()).thenReturn(5);
        fanoutService = new MessageFanoutService(memberRepository, messageRepository, outboxRepository,
                eventFactory, objectMapper, properties, deviceDirectory, routePublisher, deliveryState,
                inboxRepository);
    }

    @Test
    void offlineRecipientGetsDurableNotificationInsteadOfAnUnboundedRedisQueue() {
        EventEnvelope event = createdEvent("WRITE");
        when(deviceDirectory.activeDevices(senderId)).thenReturn(List.of());
        when(deviceDirectory.activeDevices(recipientId)).thenReturn(List.of());
        OutboxEventEntity notification = new OutboxEventEntity(UUID.randomUUID(), "notification.requested", 1,
                Instant.now(), "trace", "notification-key", "MESSAGE", messageId,
                objectMapper.createObjectNode());
        when(eventFactory.toEntity(any(DomainEvent.class), eq("MESSAGE"), eq(messageId))).thenReturn(notification);

        fanoutService.handleCreated(event);

        verify(routePublisher, never()).publishToDevice(any(), any());
        verify(outboxRepository).save(notification);
        verify(deliveryState, never()).markPending(any(), any(), any(), anyLong(), any(), anyInt());
    }

    @Test
    void onlineUsersReceiveOnEveryActiveDeviceExceptTheOriginatingDevice() {
        EventEnvelope event = createdEvent("WRITE");
        UUID senderOtherDevice = UUID.randomUUID();
        UUID recipientDeviceOne = UUID.randomUUID();
        UUID recipientDeviceTwo = UUID.randomUUID();
        when(deviceDirectory.activeDevices(senderId)).thenReturn(List.of(
                new ActiveDevice(senderId, senderDeviceId, "source-connection", "gateway-a"),
                new ActiveDevice(senderId, senderOtherDevice, "other-connection", "gateway-a")));
        when(deviceDirectory.activeDevices(recipientId)).thenReturn(List.of(
                new ActiveDevice(recipientId, recipientDeviceOne, "recipient-1", "gateway-b"),
                new ActiveDevice(recipientId, recipientDeviceTwo, "recipient-2", "gateway-c")));
        when(deliveryState.markPending(any(), any(), any(), anyLong(), any(), anyInt()))
                .thenReturn(true);

        fanoutService.handleCreated(event);

        ArgumentCaptor<ActiveDevice> devices = ArgumentCaptor.forClass(ActiveDevice.class);
        ArgumentCaptor<ObjectNode> events = ArgumentCaptor.forClass(ObjectNode.class);
        verify(routePublisher, times(3)).publishToDevice(devices.capture(), events.capture());
        assertThat(devices.getAllValues()).extracting(ActiveDevice::deviceId)
                .containsExactlyInAnyOrder(senderOtherDevice, recipientDeviceOne, recipientDeviceTwo);
        assertThat(events.getAllValues()).extracting(eventPayload -> eventPayload.get("type").asText())
                .containsOnly("message.new");
        verify(deliveryState, times(2)).markPending(any(), eq(messageId), eq(chatId), eq(42L), any(), eq(5));
    }

    @Test
    void gatewayFailureLeavesTheDurableAttemptForRetryAndIsRedeliverable() {
        EventEnvelope event = createdEvent("WRITE");
        ActiveDevice recipientDevice = new ActiveDevice(recipientId, UUID.randomUUID(), "recipient", "gateway-a");
        when(deviceDirectory.activeDevices(senderId)).thenReturn(List.of());
        when(deviceDirectory.activeDevices(recipientId)).thenReturn(List.of(recipientDevice));
        when(deliveryState.markPending(any(), any(), any(), anyLong(), any(), anyInt()))
                .thenReturn(true);
        doThrow(new IllegalStateException("gateway unavailable")).when(routePublisher)
                .publishToDevice(any(), any());

        assertThatThrownBy(() -> fanoutService.handleCreated(event))
                .isInstanceOf(IllegalStateException.class).hasMessage("gateway unavailable");

        verify(deliveryState).recordAttemptFailure(recipientId, messageId, 5);
    }

    @Test
    void largeGroupsResolveMembersAtReadTimeWithoutCreatingOfflineInboxRows() {
        EventEnvelope event = createdEvent("READ");
        when(memberRepository.findByIdChatIdAndLeftAtIsNullOrderByJoinedAtAsc(chatId)).thenReturn(List.of(
                new com.uvya.apigateway.data.domain.ChatMemberEntity(chatId, senderId,
                        com.uvya.apigateway.data.domain.ChatMemberRole.OWNER, Instant.now()),
                new com.uvya.apigateway.data.domain.ChatMemberEntity(chatId, recipientId,
                        com.uvya.apigateway.data.domain.ChatMemberRole.MEMBER, Instant.now())));
        when(deviceDirectory.activeDevices(senderId)).thenReturn(List.of());
        when(deviceDirectory.activeDevices(recipientId)).thenReturn(List.of());

        fanoutService.handleCreated(event);

        verify(inboxRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void workerRestartMayRedeliverBeforeAckButDurableStateStopsItAfterAck() {
        EventEnvelope event = createdEvent("WRITE");
        when(deviceDirectory.activeDevices(senderId)).thenReturn(List.of());
        when(deviceDirectory.activeDevices(recipientId)).thenReturn(List.of(
                new ActiveDevice(recipientId, UUID.randomUUID(), "recipient", "gateway-a")));
        when(deliveryState.markPending(any(), any(), any(), anyLong(), any(), anyInt()))
                .thenReturn(true, false);

        fanoutService.handleCreated(event);
        fanoutService.handleCreated(event);

        verify(routePublisher, times(1)).publishToDevice(any(), any());
    }

    @Test
    void pendingDeliveryIsRetriedFromDurableInboxStateAfterTheAckWindow() {
        MessageEntity message = new MessageEntity(messageId, chatId, senderId, senderDeviceId, UUID.randomUUID(),
                42, "text", "hello", null, null, Instant.now());
        UserInboxEntity inbox = new UserInboxEntity(recipientId, messageId, chatId, 42, message.getCreatedAt());
        inbox.markPending(Instant.now().minusSeconds(60));
        ActiveDevice recipientDevice = new ActiveDevice(recipientId, UUID.randomUUID(), "recipient", "gateway-a");
        when(properties.getRetryAfter()).thenReturn(java.time.Duration.ofSeconds(30));
        when(inboxRepository.findTop100ByDeliveryStateAndLastDeliveryAttemptAtBeforeOrderByLastDeliveryAttemptAtAsc(
                eq(DeliveryState.PENDING), any())).thenReturn(List.of(inbox));
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(deviceDirectory.activeDevices(recipientId)).thenReturn(List.of(recipientDevice));
        when(deliveryState.markPending(any(), any(), any(), anyLong(), any(), anyInt())).thenReturn(true);

        fanoutService.retryPending();

        verify(routePublisher).publishToDevice(eq(recipientDevice), any());
        verify(deliveryState).markPending(recipientId, messageId, chatId, 42L, message.getCreatedAt(), 5);
    }

    @Test
    void duplicateDeliveryAcknowledgementsEmitOneDurableDeliveredEvent() {
        UUID deviceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatEntity chat = new ChatEntity(chatId, ChatType.DIRECT, null, senderId, Instant.now());
        MessageEntity message = new MessageEntity(messageId, chatId, senderId, senderDeviceId, UUID.randomUUID(),
                42, "text", "hello", null, null, Instant.now());
        UserInboxEntity inbox = new UserInboxEntity(recipientId, messageId, chatId, 42, message.getCreatedAt());
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(inboxRepository.findByIdForUpdate(any())).thenReturn(Optional.of(inbox));
        when(authorizationPolicy.authorize(any(ChatAccessContext.class), eq(chat), eq(ChatAction.VIEW)))
                .thenReturn(null);
        OutboxEventEntity delivered = new OutboxEventEntity(UUID.randomUUID(), "message.delivered", 1,
                Instant.now(), "trace", "delivery-key", "MESSAGE", messageId,
                objectMapper.createObjectNode());
        when(eventFactory.toEntity(any(DomainEvent.class), eq("MESSAGE"), eq(messageId))).thenReturn(delivered);
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        MessageDeliveryService deliveryService = new MessageDeliveryService(chatRepository, memberRepository,
                messageRepository, inboxRepository, outboxRepository, eventFactory, objectMapper,
                authorizationPolicy);

        ChatAccessContext context = new ChatAccessContext(recipientId, deviceId, sessionId, "trace");
        assertThat(deliveryService.acknowledge(context, chatId, messageId)).isTrue();
        assertThat(deliveryService.acknowledge(context, chatId, messageId)).isFalse();

        assertThat(inbox.getDeliveryState()).isEqualTo(DeliveryState.DELIVERED);
        verify(outboxRepository, times(1)).save(delivered);
    }

    @Test
    void reactionAndPinEventsAreRoutedToEveryActiveDeviceWithoutCreatingMessageCopies() {
        ObjectNode payload = objectMapper.createObjectNode().put("messageId", messageId.toString())
                .put("chatId", chatId.toString()).put("userId", recipientId.toString())
                .put("reactionType", "like");
        when(memberRepository.findByIdChatIdAndLeftAtIsNullOrderByJoinedAtAsc(chatId)).thenReturn(List.of(
                new com.uvya.apigateway.data.domain.ChatMemberEntity(chatId, senderId,
                        com.uvya.apigateway.data.domain.ChatMemberRole.OWNER, Instant.now()),
                new com.uvya.apigateway.data.domain.ChatMemberEntity(chatId, recipientId,
                        com.uvya.apigateway.data.domain.ChatMemberRole.MEMBER, Instant.now())));
        when(deviceDirectory.activeDevices(senderId)).thenReturn(List.of(
                new ActiveDevice(senderId, UUID.randomUUID(), "sender", "gateway-a")));
        when(deviceDirectory.activeDevices(recipientId)).thenReturn(List.of(
                new ActiveDevice(recipientId, UUID.randomUUID(), "recipient", "gateway-b")));

        fanoutService.handleInteraction(new EventEnvelope(UUID.randomUUID(), "message.reaction.added", 1,
                Instant.now(), "trace", "correlation", "reaction-key", payload));

        ArgumentCaptor<ObjectNode> events = ArgumentCaptor.forClass(ObjectNode.class);
        verify(routePublisher, times(2)).publishToDevice(any(), events.capture());
        assertThat(events.getAllValues()).allMatch(event -> "message.reaction.added".equals(event.get("type").asText()));
        verify(inboxRepository, never()).save(any());
    }

    private EventEnvelope createdEvent(String mode) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", messageId.toString());
        payload.put("chatId", chatId.toString());
        payload.put("senderId", senderId.toString());
        payload.put("senderDeviceId", senderDeviceId.toString());
        payload.put("sequence", 42);
        payload.put("messageType", "text");
        payload.put("body", "hello");
        payload.put("version", 1);
        payload.put("status", "ACTIVE");
        payload.put("fanoutMode", mode);
        payload.putArray("recipientUserIds").add(senderId.toString()).add(recipientId.toString());
        return new EventEnvelope(UUID.randomUUID(), "message.created", 1, Instant.now(), "trace", "correlation",
                "message-key", payload);
    }
}
