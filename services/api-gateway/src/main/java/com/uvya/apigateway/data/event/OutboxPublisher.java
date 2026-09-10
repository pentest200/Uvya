package com.uvya.apigateway.data.event;

import com.uvya.apigateway.data.domain.OutboxEventEntity;

@FunctionalInterface
public interface OutboxPublisher {
    void publish(OutboxEventEntity event);
}
