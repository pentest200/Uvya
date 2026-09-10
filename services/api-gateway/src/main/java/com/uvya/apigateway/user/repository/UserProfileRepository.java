package com.uvya.apigateway.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uvya.apigateway.auth.domain.UserStatus;
import com.uvya.apigateway.user.domain.Discoverability;
import com.uvya.apigateway.user.domain.UserProfileEntity;

public interface UserProfileRepository extends JpaRepository<UserProfileEntity, UUID> {
    Optional<UserProfileEntity> findByUserId(UUID userId);

    Optional<UserProfileEntity> findByUsernameNormalized(String usernameNormalized);

    @Query("select p from UserProfileEntity p, UserEntity u where p.userId = u.id"
            + " and u.status = :status and p.discoverability = :discoverability"
            + " and (lower(p.usernameNormalized) like lower(concat('%', :query, '%'))"
            + " or lower(p.displayName) like lower(concat('%', :query, '%')))")
    Page<UserProfileEntity> searchPublic(@Param("query") String query, @Param("status") UserStatus status,
            @Param("discoverability") Discoverability discoverability, Pageable pageable);
}
