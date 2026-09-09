package com.uvya.apigateway.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "devices")
public class DeviceEntity {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(name = "user_agent", length = 512)
    private String userAgent;
    @Column(name = "last_ip", length = 64)
    private String lastIp;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant lastSeenAt;
    private Instant revokedAt;

    protected DeviceEntity() { }

    public DeviceEntity(UUID id, UUID userId, String name, String userAgent, String lastIp, Instant now) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.userAgent = userAgent;
        this.lastIp = lastIp;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public String getUserAgent() { return userAgent; }
    public String getLastIp() { return lastIp; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void touch(String userAgent, String lastIp, Instant now) {
        this.userAgent = userAgent;
        this.lastIp = lastIp;
        this.lastSeenAt = now;
    }
    public void revoke(Instant now) { revokedAt = now; }
}
