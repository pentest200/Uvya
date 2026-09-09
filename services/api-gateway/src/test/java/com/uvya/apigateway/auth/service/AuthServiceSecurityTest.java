package com.uvya.apigateway.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.uvya.apigateway.auth.config.AuthProperties;
import com.uvya.apigateway.auth.domain.AuthSessionEntity;
import com.uvya.apigateway.auth.domain.DeviceEntity;
import com.uvya.apigateway.auth.repository.AuthSessionRepository;
import com.uvya.apigateway.auth.repository.DeviceRepository;
import com.uvya.apigateway.auth.repository.UserRepository;
import com.uvya.apigateway.auth.web.LoginRequest;

@ExtendWith(MockitoExtension.class)
class AuthServiceSecurityTest {
    @Mock private UserRepository userRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private AuthSessionRepository sessionRepository;
    @Mock private AuthRateLimiter rateLimiter;
    @Mock private AuditService auditService;
    private AuthService authService;
    private AuthProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.getJwt().setAccessTokenTtl(java.time.Duration.ofMinutes(15));
        properties.getJwt().setRefreshTokenTtl(java.time.Duration.ofDays(30));
        properties.getRateLimit().setMaxFailedAttempts(5);
        properties.getRateLimit().setLockDuration(java.time.Duration.ofMinutes(15));
        properties.setRefreshTokenPepper("test-refresh-token-pepper-with-at-least-32-chars");
        authService = new AuthService(userRepository, deviceRepository, sessionRepository,
                new BCryptPasswordEncoder(4), properties, new TokenHashingService(properties),
                org.mockito.Mockito.mock(TokenService.class), rateLimiter, auditService);
    }

    @Test
    void invalidCredentialsHaveTheSameFailureForKnownAndUnknownAccounts() {
        LoginRequest request = new LoginRequest("person@example.com", "wrong-password", null, null);
        when(userRepository.findByEmail("person@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request, new RequestContext("127.0.0.1", "r1", "ua")))
                .isInstanceOf(AuthException.class)
                .hasMessage("Invalid email or password");
        verify(rateLimiter).checkLogin("127.0.0.1", "person@example.com");
    }

    @Test
    void aReplayedRotatedTokenRevokesItsReplacementAndDevice() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID oldId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        DeviceEntity device = new DeviceEntity(deviceId, userId, "Laptop", "ua", "127.0.0.1", Instant.now());
        AuthSessionEntity old = new AuthSessionEntity(oldId, userId, deviceId, "old-hash", Instant.now().plusSeconds(60), Instant.now());
        old.replaceWith(replacementId, Instant.now());
        AuthSessionEntity replacement = new AuthSessionEntity(replacementId, userId, deviceId, "new-hash",
                Instant.now().plusSeconds(60), Instant.now());
        when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(old));
        when(sessionRepository.findById(replacementId)).thenReturn(Optional.of(replacement));
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> authService.refresh("old-token", new RequestContext("127.0.0.1", "r1", "ua")))
                .isInstanceOf(AuthException.class)
                .hasMessage("Invalid refresh token");
        verify(sessionRepository).save(replacement);
        verify(deviceRepository).save(device);
        verify(auditService).record("REFRESH_REPLAY_DETECTED", userId, deviceId, oldId,
                new RequestContext("127.0.0.1", "r1", "ua"));
    }
}
