package com.uvya.apigateway.data.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uvya.apigateway.data.domain.BlockedUserEntity;
import com.uvya.apigateway.data.domain.BlockedUserId;

public interface BlockedUserRepository extends JpaRepository<BlockedUserEntity, BlockedUserId> {
    @Query("select count(b) > 0 from BlockedUserEntity b"
            + " where b.id.blockerUserId = :blocker and b.id.blockedUserId = :blocked")
    boolean existsBlock(@Param("blocker") UUID blockerUserId, @Param("blocked") UUID blockedUserId);
}
