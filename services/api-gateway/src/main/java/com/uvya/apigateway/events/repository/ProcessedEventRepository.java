package com.uvya.apigateway.events.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.events.domain.ProcessedEventEntity;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {
    boolean existsByConsumerGroupAndEventId(String consumerGroup, UUID eventId);

    long countByConsumerGroup(String consumerGroup);
}
