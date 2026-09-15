package com.uvya.apigateway.realtime;

import java.util.UUID;

public record ActiveDevice(UUID userId, UUID deviceId, String connectionId, String gatewayId) {
}
