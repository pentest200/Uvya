package com.uvya.apigateway.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.uvya.apigateway.auth.config.AuthProperties;

class TokenHashingServiceTest {
    @Test
    void hashesAreOneWayAndPepperBound() {
        AuthProperties properties = new AuthProperties();
        properties.setRefreshTokenPepper("test-refresh-token-pepper-with-at-least-32-chars");
        TokenHashingService hashing = new TokenHashingService(properties);

        String hash = hashing.hash("opaque-token");

        assertThat(hash).hasSize(64).doesNotContain("opaque-token");
        assertThat(hashing.matches("opaque-token", hash)).isTrue();
        assertThat(hashing.matches("other-token", hash)).isFalse();
    }
}
