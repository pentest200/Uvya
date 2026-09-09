package com.uvya.apigateway.auth.web;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(UUID id, UUID deviceId, String deviceName, String userAgent, String ipAddress,
        Instant createdAt, Instant lastUsedAt, Instant refreshTokenExpiresAt) {
}
