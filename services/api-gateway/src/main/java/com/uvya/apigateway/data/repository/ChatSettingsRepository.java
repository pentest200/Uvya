package com.uvya.apigateway.data.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.data.domain.ChatSettingsEntity;

public interface ChatSettingsRepository extends JpaRepository<ChatSettingsEntity, UUID> {
    Optional<ChatSettingsEntity> findByChatId(UUID chatId);
}
