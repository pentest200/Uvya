package com.uvya.apigateway.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.uvya.apigateway.auth.config.AuthProperties;

@Service
public class TokenHashingService {
    private final AuthProperties properties;

    public TokenHashingService(AuthProperties properties) {
        this.properties = properties;
        if (properties.getRefreshTokenPepper() == null || properties.getRefreshTokenPepper().length() < 32) {
            throw new IllegalStateException("UVYA_REFRESH_TOKEN_PEPPER must contain at least 32 characters");
        }
    }

    public String hash(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getRefreshTokenPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash authentication token", exception);
        }
    }

    public boolean matches(String token, String expectedHash) {
        return MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
