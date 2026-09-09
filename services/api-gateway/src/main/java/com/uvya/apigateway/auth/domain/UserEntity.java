package com.uvya.apigateway.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_users")
public class UserEntity {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 320)
    private String email;
    @Column(name = "password_hash", length = 255)
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;
    @Column(nullable = false)
    private int failedLoginAttempts;
    private Instant lockedUntil;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected UserEntity() { }

    public UserEntity(UUID id, String email, String passwordHash, Instant now) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public UserStatus getStatus() { return status; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void markLoginFailure(Instant until, int maxAttempts) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            status = UserStatus.LOCKED;
            lockedUntil = until;
        }
        updatedAt = Instant.now();
    }
    public void markLoginSuccess() {
        failedLoginAttempts = 0;
        lockedUntil = null;
        status = UserStatus.ACTIVE;
        updatedAt = Instant.now();
    }
    public boolean isLocked(Instant now) {
        return status == UserStatus.DISABLED || (status == UserStatus.LOCKED
                && lockedUntil != null && lockedUntil.isAfter(now));
    }
}
