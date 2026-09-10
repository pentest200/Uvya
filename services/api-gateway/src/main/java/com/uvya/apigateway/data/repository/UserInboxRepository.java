package com.uvya.apigateway.data.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.data.domain.UserInboxEntity;
import com.uvya.apigateway.data.domain.UserInboxId;

public interface UserInboxRepository extends JpaRepository<UserInboxEntity, UserInboxId> {
    List<UserInboxEntity> findByIdUserIdOrderBySequenceAsc(UUID userId);
}
