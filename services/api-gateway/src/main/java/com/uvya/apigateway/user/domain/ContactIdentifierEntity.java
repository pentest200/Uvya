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
@Table(name = "contact_identifiers", uniqueConstraints = {
        @UniqueConstraint(name = "uq_contact_identifiers_owner_value",
                columnNames = {"owner_user_id", "identifier_type", "identifier_hash"})
})
public class ContactIdentifierEntity {
    @Id
    private UUID id;
    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;
    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false, length = 16)
    private ContactIdentifierType identifierType;
    @Column(name = "identifier_hash", nullable = false, length = 64)
    private String identifierHash;
    @Column(nullable = false)
    private Instant createdAt;

    protected ContactIdentifierEntity() { }

    public ContactIdentifierEntity(UUID ownerUserId, ContactIdentifierType identifierType,
            String identifierHash, Instant createdAt) {
        id = UUID.randomUUID();
        this.ownerUserId = ownerUserId;
        this.identifierType = identifierType;
        this.identifierHash = identifierHash;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public ContactIdentifierType getIdentifierType() { return identifierType; }
    public String getIdentifierHash() { return identifierHash; }
    public Instant getCreatedAt() { return createdAt; }
}
