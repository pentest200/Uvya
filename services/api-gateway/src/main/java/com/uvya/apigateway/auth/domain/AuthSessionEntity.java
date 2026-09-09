package com.uvya.apigateway.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;
    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 64)
    private String refreshTokenHash;
    @Column(name = "refresh_token_expires_at", nullable = false)
    private Instant refreshTokenExpiresAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant lastUsedAt;
    private Instant revokedAt;
    @Column(length = 64)
    private String revokeReason;
    private UUID replacedBySessionId;

    protected AuthSessionEntity() { }

    public AuthSessionEntity(UUID id, UUID userId, UUID deviceId, String refreshTokenHash,
            Instant expiresAt, Instant now) {
        this.id = id;
        this.userId = userId;
        this.deviceId = deviceId;
        this.refreshTokenHash = refreshTokenHash;
        this.refreshTokenExpiresAt = expiresAt;
        this.createdAt = now;
        this.lastUsedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getDeviceId() { return deviceId; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
    public Instant getRefreshTokenExpiresAt() { return refreshTokenExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public UUID getReplacedBySessionId() { return replacedBySessionId; }
    public boolean isExpired(Instant now) { return !refreshTokenExpiresAt.isAfter(now); }
    public void revoke(String reason, Instant now) {
        revokedAt = now;
        revokeReason = reason;
    }
    public void replaceWith(UUID newSessionId, Instant now) {
        revoke("ROTATED", now);
        replacedBySessionId = newSessionId;
        lastUsedAt = now;
    }
}
