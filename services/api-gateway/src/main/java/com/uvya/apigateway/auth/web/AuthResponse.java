package com.uvya.apigateway.auth.web;

import java.util.UUID;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, UUID sessionId) {
    @Override
    public String toString() {
        return "AuthResponse[accessToken=[REDACTED], tokenType=" + tokenType + ", expiresIn=" + expiresIn
                + ", sessionId=" + sessionId + "]";
    }
}
