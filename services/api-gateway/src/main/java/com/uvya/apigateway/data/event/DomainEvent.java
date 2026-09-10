package com.uvya.apigateway.data.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainEvent(UUID eventId, String eventType, int eventVersion, Instant occurredAt,
        String traceId, String idempotencyKey, JsonNode payload) {
    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(payload, "payload");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
    }
}
