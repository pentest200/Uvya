package com.uvya.apigateway.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.user.domain.ContactIdentifierType;
import com.uvya.apigateway.user.domain.UserIdentifierEntity;

public interface UserIdentifierRepository extends JpaRepository<UserIdentifierEntity, UUID> {
    Optional<UserIdentifierEntity> findFirstByIdentifierTypeAndIdentifierHash(ContactIdentifierType identifierType,
            String identifierHash);

    Optional<UserIdentifierEntity> findByUserIdAndIdentifierType(UUID userId, ContactIdentifierType identifierType);
}
