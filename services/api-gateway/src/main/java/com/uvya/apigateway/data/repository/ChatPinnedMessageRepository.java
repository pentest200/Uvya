package com.uvya.apigateway.data.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.data.domain.ChatPinnedMessageEntity;
import com.uvya.apigateway.data.domain.ChatPinnedMessageId;

public interface ChatPinnedMessageRepository extends JpaRepository<ChatPinnedMessageEntity, ChatPinnedMessageId> {
    List<ChatPinnedMessageEntity> findTop50ByChatIdOrderByPinnedAtDesc(UUID chatId);
}
