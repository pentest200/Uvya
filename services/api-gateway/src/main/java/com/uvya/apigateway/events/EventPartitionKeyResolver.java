package com.uvya.apigateway.events;

import java.util.UUID;

import org.springframework.stereotype.Component;

public interface EventPartitionKeyResolver {
    String key(EventEnvelope event);

    @Component
    class Default implements EventPartitionKeyResolver {
        @Override
        public String key(EventEnvelope event) {
            String type = event.eventType();
            String payloadKey;
            if (type.startsWith("message.reaction.")) {
                payloadKey = value(event, "messageId");
            } else if (type.startsWith("message.")) {
                payloadKey = value(event, "chatId");
            } else if (type.startsWith("presence.") || type.startsWith("notification.")) {
                payloadKey = value(event, "userId");
            } else {
                payloadKey = null;
            }
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
