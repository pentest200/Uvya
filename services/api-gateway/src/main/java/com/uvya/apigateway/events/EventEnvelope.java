package com.uvya.apigateway.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.uvya.apigateway.data.domain.OutboxEventEntity;

public record EventEnvelope(UUID eventId, String eventType, int eventVersion, Instant occurredAt, String traceId,
        String correlationId, String idempotencyKey, JsonNode payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(payload, "payload");
    }

    public static EventEnvelope from(OutboxEventEntity event) {
        return new EventEnvelope(event.getEventId(), event.getEventType(), event.getEventVersion(),
                event.getOccurredAt(), event.getTraceId(), event.getCorrelationId(), event.getIdempotencyKey(),
                event.getPayload());
    }
}
