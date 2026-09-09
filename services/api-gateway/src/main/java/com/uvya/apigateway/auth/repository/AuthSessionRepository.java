package com.uvya.apigateway.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.uvya.apigateway.auth.domain.AuthSessionEntity;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuthSessionEntity> findByRefreshTokenHash(String refreshTokenHash);
    List<AuthSessionEntity> findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(UUID userId);
    Optional<AuthSessionEntity> findByIdAndUserId(UUID id, UUID userId);
}
