package com.uvya.apigateway.user.domain;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_profiles", uniqueConstraints = {
        @UniqueConstraint(name = "uq_user_profiles_username_normalized",
                columnNames = "username_normalized")
})
public class UserProfileEntity {
    @Id
    @Column(name = "user_id")
    private UUID userId;
    @Column(length = 32)
    private String username;
    @Column(name = "username_normalized", length = 32)
    private String usernameNormalized;
    @Column(name = "display_name", length = 120)
    private String displayName;
    @Column(length = 500)
    private String bio;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "avatar_metadata", nullable = false)
    private JsonNode avatarMetadata;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "privacy_settings", nullable = false)
    private JsonNode privacySettings;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Discoverability discoverability;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected UserProfileEntity() { }

    public UserProfileEntity(UUID userId, JsonNode avatarMetadata, JsonNode privacySettings, Instant now) {
        this.userId = userId;
        this.avatarMetadata = avatarMetadata;
        this.privacySettings = privacySettings;
        discoverability = Discoverability.CONTACTS_ONLY;
        createdAt = now;
        updatedAt = now;
    }

    public UUID getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getUsernameNormalized() { return usernameNormalized; }
    public String getDisplayName() { return displayName; }
    public String getBio() { return bio; }
    public JsonNode getAvatarMetadata() { return avatarMetadata; }
    public JsonNode getPrivacySettings() { return privacySettings; }
    public Discoverability getDiscoverability() { return discoverability; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String username, String usernameNormalized, String displayName, String bio,
            JsonNode avatarMetadata, JsonNode privacySettings, Discoverability discoverability, Instant now) {
        if (username != null || usernameNormalized != null) {
            this.username = username;
            this.usernameNormalized = usernameNormalized;
        }
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (bio != null) {
            this.bio = bio;
        }
        if (avatarMetadata != null) {
            this.avatarMetadata = avatarMetadata;
        }
        if (privacySettings != null) {
            this.privacySettings = privacySettings;
        }
        if (discoverability != null) {
            this.discoverability = discoverability;
        }
        updatedAt = now;
    }
}
