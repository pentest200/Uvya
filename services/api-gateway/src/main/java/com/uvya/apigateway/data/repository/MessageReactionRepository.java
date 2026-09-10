package com.uvya.apigateway.data.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.data.domain.MessageReactionEntity;
import com.uvya.apigateway.data.domain.MessageReactionId;

public interface MessageReactionRepository extends JpaRepository<MessageReactionEntity, MessageReactionId> {
    List<MessageReactionEntity> findByIdMessageIdOrderByCreatedAtAsc(UUID messageId);
}
