package com.uvya.apigateway.data.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.uvya.apigateway.data.domain.OutboxEventEntity;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    long countByPublishedAtIsNull();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OutboxEventEntity> findTop100ByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByAvailableAtAscOccurredAtAsc(
            Instant now);
}
