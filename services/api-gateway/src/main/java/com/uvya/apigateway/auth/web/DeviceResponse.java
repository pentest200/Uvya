package com.uvya.apigateway.auth.web;

import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(UUID id, String name, String userAgent, String lastIp, Instant createdAt,
        Instant lastSeenAt) {
}
