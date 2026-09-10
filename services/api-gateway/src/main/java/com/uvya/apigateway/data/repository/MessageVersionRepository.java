package com.uvya.apigateway.data.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.data.domain.MessageVersionEntity;

public interface MessageVersionRepository extends JpaRepository<MessageVersionEntity, UUID> {
    List<MessageVersionEntity> findByMessageIdOrderByVersionAsc(UUID messageId);
}
