package com.uvya.apigateway.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "auth_audit_logs")
public class AuthAuditLogEntity {
    @Id
    private UUID id;
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    private UUID userId;
    private UUID deviceId;
    private UUID sessionId;
    @Column(name = "ip_address", length = 64)
    private String ipAddress;
    @Column(name = "request_id", length = 128)
    private String requestId;
    @Column(nullable = false, length = 4096)
    private String metadata;
    @Column(nullable = false)
    private Instant occurredAt;

    protected AuthAuditLogEntity() { }

    public AuthAuditLogEntity(String eventType, UUID userId, UUID deviceId, UUID sessionId,
            String ipAddress, String requestId, String metadata, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.eventType = eventType;
        this.userId = userId;
        this.deviceId = deviceId;
        this.sessionId = sessionId;
        this.ipAddress = ipAddress;
        this.requestId = requestId;
        this.metadata = metadata;
        this.occurredAt = occurredAt;
    }
}
