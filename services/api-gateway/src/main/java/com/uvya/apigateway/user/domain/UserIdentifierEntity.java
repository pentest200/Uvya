package com.uvya.apigateway.user.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "user_identifiers", uniqueConstraints = {
        @UniqueConstraint(name = "uq_user_identifiers_type_hash",
                columnNames = {"identifier_type", "identifier_hash"}),
        @UniqueConstraint(name = "uq_user_identifiers_user_type",
                columnNames = {"user_id", "identifier_type"})
})
public class UserIdentifierEntity {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false, length = 16)
    private ContactIdentifierType identifierType;
    @Column(name = "identifier_hash", nullable = false, length = 64)
    private String identifierHash;
    @Column(nullable = false)
    private boolean verified;
    @Column(nullable = false)
    private Instant createdAt;

    protected UserIdentifierEntity() { }

    public UserIdentifierEntity(UUID userId, ContactIdentifierType identifierType, String identifierHash,
            boolean verified, Instant createdAt) {
        id = UUID.randomUUID();
        this.userId = userId;
        this.identifierType = identifierType;
        this.identifierHash = identifierHash;
        this.verified = verified;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public ContactIdentifierType getIdentifierType() { return identifierType; }
    public String getIdentifierHash() { return identifierHash; }
    public boolean isVerified() { return verified; }
    public Instant getCreatedAt() { return createdAt; }
}
