package com.uvya.apigateway.data.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uvya.apigateway.data.domain.MessageEntity;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {
    List<MessageEntity> findByChatIdOrderBySequenceNumberAsc(UUID chatId);

    Optional<MessageEntity> findByChatIdAndClientMessageIdAndSenderId(UUID chatId,
            UUID clientMessageId, UUID senderId);

    List<MessageEntity> findByChatIdAndSequenceNumberLessThanOrderBySequenceNumberDesc(UUID chatId,
            long sequenceNumber, Pageable pageable);

    List<MessageEntity> findByChatIdOrderBySequenceNumberDesc(UUID chatId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MessageEntity m where m.messageId = :messageId")
    Optional<MessageEntity> findByIdForUpdate(@Param("messageId") UUID messageId);
}
