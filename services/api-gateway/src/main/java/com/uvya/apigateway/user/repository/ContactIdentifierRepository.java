package com.uvya.apigateway.user.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.user.domain.ContactIdentifierEntity;
import com.uvya.apigateway.user.domain.ContactIdentifierType;

public interface ContactIdentifierRepository extends JpaRepository<ContactIdentifierEntity, UUID> {
    boolean existsByOwnerUserIdAndIdentifierTypeAndIdentifierHash(UUID ownerUserId,
            ContactIdentifierType identifierType, String identifierHash);
}
