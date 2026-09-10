package com.uvya.apigateway.chat.service;

import java.util.UUID;

public record ChatAccessContext(UUID userId, UUID deviceId, UUID sessionId, String traceId) {
}
