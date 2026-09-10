package com.uvya.apigateway.data.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uvya.apigateway.data.domain.ChatEntity;

public interface ChatRepository extends JpaRepository<ChatEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChatEntity c where c.id = :chatId")
    Optional<ChatEntity> findByIdForUpdate(@Param("chatId") UUID chatId);
}
