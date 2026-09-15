package com.uvya.apigateway.data.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.time.Instant;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uvya.apigateway.data.domain.UserInboxEntity;
import com.uvya.apigateway.data.domain.UserInboxId;
import com.uvya.apigateway.data.domain.DeliveryState;

public interface UserInboxRepository extends JpaRepository<UserInboxEntity, UserInboxId> {
    List<UserInboxEntity> findByIdUserIdOrderBySequenceAsc(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from UserInboxEntity i where i.id = :id")
    Optional<UserInboxEntity> findByIdForUpdate(@Param("id") UserInboxId id);

    List<UserInboxEntity> findByIdUserIdAndChatIdAndSequenceLessThanEqual(UUID userId, UUID chatId, long sequence);

    List<UserInboxEntity> findTop100ByDeliveryStateAndLastDeliveryAttemptAtBeforeOrderByLastDeliveryAttemptAtAsc(
            DeliveryState state, Instant before);
}
