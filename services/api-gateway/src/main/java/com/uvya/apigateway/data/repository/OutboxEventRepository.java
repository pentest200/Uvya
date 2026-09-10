package com.uvya.apigateway.data.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.data.domain.OutboxEventEntity;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    List<OutboxEventEntity> findTop100ByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByAvailableAtAscOccurredAtAsc(
            Instant now);
}
