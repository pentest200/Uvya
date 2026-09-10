package com.uvya.apigateway.data.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.data.domain.IdempotencyKeyEntity;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {
    Optional<IdempotencyKeyEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
