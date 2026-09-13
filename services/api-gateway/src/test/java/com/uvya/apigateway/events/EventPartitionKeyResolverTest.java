package com.uvya.apigateway.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class EventPartitionKeyResolverTest {
    private final EventPartitionKeyResolver resolver = new EventPartitionKeyResolver.Default();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void messageEventsUseChatAndReactionEventsUseMessageAsPartitionKey() {
        UUID chatId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        EventEnvelope message = event("message.created", objectMapper.createObjectNode()
                .put("chatId", chatId.toString()).put("messageId", messageId.toString()));
        EventEnvelope reaction = event("message.reaction.added", objectMapper.createObjectNode()
                .put("chatId", chatId.toString()).put("messageId", messageId.toString()));

        assertThat(resolver.key(message)).isEqualTo(chatId.toString());
        assertThat(resolver.key(reaction)).isEqualTo(messageId.toString());
    }

    @Test
    void notificationAndPresenceEventsUseUserAsPartitionKey() {
        UUID userId = UUID.randomUUID();
        EventEnvelope notification = event("notification.requested", objectMapper.createObjectNode()
                .put("userId", userId.toString()));
        EventEnvelope presence = event("presence.updated", objectMapper.createObjectNode()
                .put("userId", userId.toString()));

        assertThat(resolver.key(notification)).isEqualTo(userId.toString());
        assertThat(resolver.key(presence)).isEqualTo(userId.toString());
    }

    private EventEnvelope event(String type, com.fasterxml.jackson.databind.JsonNode payload) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), "trace", "correlation", "key", payload);
    }
}
