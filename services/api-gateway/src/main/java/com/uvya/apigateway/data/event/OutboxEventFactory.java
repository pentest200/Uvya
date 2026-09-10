package com.uvya.apigateway.data.event;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.uvya.apigateway.data.domain.OutboxEventEntity;

@Component
public class OutboxEventFactory {
    public OutboxEventEntity toEntity(DomainEvent event, String aggregateType, UUID aggregateId) {
        return new OutboxEventEntity(event.eventId(), event.eventType(), event.eventVersion(), event.occurredAt(),
                event.traceId(), event.idempotencyKey(), aggregateType, aggregateId, event.payload());
    }
}
