package com.uvya.apigateway.events;

import java.util.UUID;

import org.springframework.stereotype.Component;

public interface EventPartitionKeyResolver {
    String key(EventEnvelope event);

    @Component
    class Default implements EventPartitionKeyResolver {
        @Override
        public String key(EventEnvelope event) {
            String payloadKey = switch (event.eventType()) {
                case String type when type.startsWith("message.reaction.") -> value(event, "messageId");
                case String type when type.startsWith("message.") -> value(event, "chatId");
                case String type when type.startsWith("presence.") -> value(event, "userId");
                case String type when type.startsWith("notification.") -> value(event, "userId");
                default -> null;
            };
            return payloadKey != null ? payloadKey : event.payload().path("chatId").asText(event.eventId().toString());
        }

        private String value(EventEnvelope event, String field) {
            String value = event.payload().path(field).asText(null);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required for " + event.eventType());
            }
            try {
                return UUID.fromString(value).toString();
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(field + " must be a UUID for " + event.eventType(), exception);
            }
        }
    }
}
