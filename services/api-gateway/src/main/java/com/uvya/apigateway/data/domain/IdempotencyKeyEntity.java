package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "idempotency_keys", uniqueConstraints = {
        @UniqueConstraint(name = "uq_idempotency_keys_user_key", columnNames = {"user_id", "idempotency_key"})
})
public class IdempotencyKeyEntity {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;
    @Column(name = "resource_id")
    private UUID resourceId;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant expiresAt;

    protected IdempotencyKeyEntity() { }

    public IdempotencyKeyEntity(UUID id, UUID userId, String idempotencyKey, String requestHash,
            String resourceType, UUID resourceId, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
