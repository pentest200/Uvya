package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "blocked_users")
public class BlockedUserEntity {
    @EmbeddedId
    private BlockedUserId id;
    @Column(nullable = false)
    private Instant createdAt;

    protected BlockedUserEntity() { }

    public BlockedUserEntity(UUID blockerUserId, UUID blockedUserId, Instant createdAt) {
        id = new BlockedUserId(blockerUserId, blockedUserId);
        this.createdAt = createdAt;
    }

    public BlockedUserId getId() { return id; }
    public UUID getBlockerUserId() { return id.getBlockerUserId(); }
    public UUID getBlockedUserId() { return id.getBlockedUserId(); }
    public Instant getCreatedAt() { return createdAt; }
}
