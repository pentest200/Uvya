package com.uvya.apigateway.data.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.data.domain.MessageEntity;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {
    List<MessageEntity> findByChatIdOrderBySequenceNumberAsc(UUID chatId);

    Optional<MessageEntity> findByChatIdAndClientMessageIdAndSenderId(UUID chatId,
            UUID clientMessageId, UUID senderId);
}
