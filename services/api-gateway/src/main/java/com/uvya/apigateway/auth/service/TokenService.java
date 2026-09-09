package com.uvya.apigateway.auth.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.uvya.apigateway.auth.config.AuthProperties;

@Service
public class TokenService {
    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;

    public TokenService(JwtEncoder jwtEncoder, AuthProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public String accessToken(UUID userId, UUID sessionId, UUID deviceId, Instant now) {
        Instant expiresAt = now.plus(properties.getJwt().getAccessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("sid", sessionId.toString())
                .claim("did", deviceId.toString())
                .claim("scope", "user")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    public String newRefreshToken() {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(SecureRandomHolder.INSTANCE.generateSeed(32));
    }

    private static final class SecureRandomHolder {
        private static final java.security.SecureRandom INSTANCE = new java.security.SecureRandom();
    }
}
