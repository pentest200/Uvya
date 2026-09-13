package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events", uniqueConstraints = {
        @UniqueConstraint(name = "uq_outbox_events_type_key", columnNames = {"event_type", "idempotency_key"})
})
public class OutboxEventEntity {
    @Id
    @Column(name = "event_id")
    private UUID eventId;
    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;
    @Column(name = "event_version", nullable = false)
    private int eventVersion;
    @Column(nullable = false)
    private Instant occurredAt;
    @Column(name = "trace_id", nullable = false, length = 128)
    private String traceId;
    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;
    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant availableAt;
    private Instant publishedAt;
    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    protected OutboxEventEntity() { }

    public OutboxEventEntity(UUID eventId, String eventType, int eventVersion, Instant occurredAt,
            String traceId, String idempotencyKey, String aggregateType, UUID aggregateId,
            JsonNode payload) {
        this(eventId, eventType, eventVersion, occurredAt, traceId, traceId, idempotencyKey, aggregateType,
                aggregateId, payload);
    }

    public OutboxEventEntity(UUID eventId, String eventType, int eventVersion, Instant occurredAt,
            String traceId, String correlationId, String idempotencyKey, String aggregateType, UUID aggregateId,
            JsonNode payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.occurredAt = occurredAt;
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        createdAt = occurredAt;
        availableAt = occurredAt;
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getTraceId() { return traceId; }
    public String getCorrelationId() { return correlationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public JsonNode getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getPublishAttempts() { return publishAttempts; }

    public void markPublishAttempt(Instant now) {
        publishAttempts++;
        availableAt = now;
    }

    public void markPublished(Instant now) { publishedAt = now; }
}
