package com.uvya.apigateway.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.auth.domain.DeviceEntity;

public interface DeviceRepository extends JpaRepository<DeviceEntity, UUID> {
    Optional<DeviceEntity> findByIdAndUserId(UUID id, UUID userId);
    List<DeviceEntity> findByUserIdOrderByLastSeenAtDesc(UUID userId);
}
