package com.uvya.apigateway.auth.service;

import java.time.Duration;
import java.util.UUID;

public record IssuedTokens(String accessToken, String refreshToken, Duration refreshTokenTtl, UUID sessionId) {
}
