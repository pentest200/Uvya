package com.uvya.apigateway.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uvya.apigateway.auth.config.AuthProperties;
import com.uvya.apigateway.auth.domain.AuthSessionEntity;
import com.uvya.apigateway.auth.domain.DeviceEntity;
import com.uvya.apigateway.auth.domain.UserEntity;
import com.uvya.apigateway.auth.repository.AuthSessionRepository;
import com.uvya.apigateway.auth.repository.DeviceRepository;
import com.uvya.apigateway.auth.repository.UserRepository;
import com.uvya.apigateway.auth.web.DeviceRegistrationRequest;
import com.uvya.apigateway.auth.web.DeviceResponse;
import com.uvya.apigateway.auth.web.LoginRequest;
import com.uvya.apigateway.auth.web.RegisterRequest;
import com.uvya.apigateway.auth.web.SessionResponse;

@Service
public class AuthService {
    private static final String GENERIC_CREDENTIAL_ERROR = "Invalid email or password";
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final AuthSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final TokenHashingService tokenHashingService;
    private final TokenService tokenService;
    private final AuthRateLimiter rateLimiter;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository, DeviceRepository deviceRepository,
            AuthSessionRepository sessionRepository, PasswordEncoder passwordEncoder,
            AuthProperties properties, TokenHashingService tokenHashingService, TokenService tokenService,
            AuthRateLimiter rateLimiter, AuditService auditService) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.tokenHashingService = tokenHashingService;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
    }

    @Transactional(noRollbackFor = AuthException.class)
    public IssuedTokens register(RegisterRequest request, RequestContext context) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            auditService.record("REGISTER_REJECTED", null, null, null, context);
            throw new AuthException("Unable to create account");
        }
        Instant now = Instant.now();
        UserEntity user = new UserEntity(UUID.randomUUID(), email, passwordEncoder.encode(request.password()), now);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new AuthException("Unable to create account");
        }
        DeviceEntity device = createDevice(user.getId(), request.deviceId(), request.deviceName(), context);
        IssuedTokens tokens = createSession(user, device, context, now);
        auditService.record("REGISTER_SUCCESS", user.getId(), device.getId(), tokens.sessionId(), context);
        return tokens;
    }

    @Transactional(noRollbackFor = AuthException.class)
    public IssuedTokens login(LoginRequest request, RequestContext context) {
        String email = normalizeEmail(request.email());
        rateLimiter.checkLogin(context.ipAddress(), email);
        UserEntity user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.isLocked(Instant.now())
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            if (user != null && user.getStatus() != com.uvya.apigateway.auth.domain.UserStatus.DISABLED) {
                user.markLoginFailure(Instant.now().plus(properties.getRateLimit().getLockDuration()),
                        properties.getRateLimit().getMaxFailedAttempts());
                userRepository.save(user);
                auditService.record("LOGIN_FAILURE", user.getId(), null, null, context);
            } else {
                auditService.record("LOGIN_FAILURE", null, null, null, context);
            }
            rateLimiter.recordFailure(context.ipAddress(), email);
            throw new AuthException(GENERIC_CREDENTIAL_ERROR);
        }
        user.markLoginSuccess();
        userRepository.save(user);
        rateLimiter.clearFailures(context.ipAddress(), email);
        DeviceEntity device = createDevice(user.getId(), request.deviceId(), request.deviceName(), context);
        IssuedTokens tokens = createSession(user, device, context, Instant.now());
        auditService.record("LOGIN_SUCCESS", user.getId(), device.getId(), tokens.sessionId(), context);
        return tokens;
    }

    @Transactional(noRollbackFor = AuthException.class)
    public IssuedTokens refresh(String refreshToken, RequestContext context) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthException("Invalid refresh token");
        }
        Instant now = Instant.now();
        AuthSessionEntity oldSession = sessionRepository.findByRefreshTokenHash(tokenHashingService.hash(refreshToken))
                .orElseThrow(() -> new AuthException("Invalid refresh token"));
        if (oldSession.getRevokedAt() != null) {
            revokeReplayLineage(oldSession, now, context);
            throw new AuthException("Invalid refresh token");
        }
        if (oldSession.isExpired(now)) {
            oldSession.revoke("EXPIRED", now);
            sessionRepository.save(oldSession);
            auditService.record("REFRESH_EXPIRED", oldSession.getUserId(), oldSession.getDeviceId(), oldSession.getId(), context);
            throw new AuthException("Invalid refresh token");
        }
        UserEntity user = userRepository.findById(oldSession.getUserId())
                .orElseThrow(() -> new AuthException("Invalid refresh token"));
        DeviceEntity device = deviceRepository.findById(oldSession.getDeviceId())
                .orElseThrow(() -> new AuthException("Invalid refresh token"));
        if (user.isLocked(now) || device.getRevokedAt() != null) {
            throw new AuthException("Invalid refresh token");
        }
        device.touch(device.getUserAgent(), context.ipAddress(), now);
        deviceRepository.save(device);
        String rawRefreshToken = tokenService.newRefreshToken();
        AuthSessionEntity newSession = new AuthSessionEntity(UUID.randomUUID(), user.getId(), device.getId(),
                tokenHashingService.hash(rawRefreshToken), now.plus(properties.getJwt().getRefreshTokenTtl()), now);
        oldSession.replaceWith(newSession.getId(), now);
        sessionRepository.save(oldSession);
        sessionRepository.save(newSession);
        String accessToken = tokenService.accessToken(user.getId(), newSession.getId(), device.getId(), now);
        auditService.record("REFRESH_SUCCESS", user.getId(), device.getId(), newSession.getId(), context);
        return new IssuedTokens(accessToken, rawRefreshToken, properties.getJwt().getRefreshTokenTtl(), newSession.getId());
    }

    @Transactional
    public void logout(String refreshToken, UUID userId, RequestContext context) {
        if (refreshToken == null || refreshToken.isBlank()) {
            auditService.record("LOGOUT_NOOP", userId, null, null, context);
            return;
        }
        AuthSessionEntity session = sessionRepository.findByRefreshTokenHash(tokenHashingService.hash(refreshToken))
                .orElse(null);
        if (session != null && (userId == null || userId.equals(session.getUserId()))) {
            session.revoke("LOGOUT", Instant.now());
            sessionRepository.save(session);
            auditService.record("LOGOUT_SUCCESS", session.getUserId(), session.getDeviceId(), session.getId(), context);
        } else {
            auditService.record("LOGOUT_NOOP", userId, null, null, context);
        }
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> sessions(UUID userId) {
        List<AuthSessionEntity> sessions = sessionRepository.findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(userId);
        Map<UUID, DeviceEntity> devices = deviceRepository.findByUserIdOrderByLastSeenAtDesc(userId).stream()
                .collect(Collectors.toMap(DeviceEntity::getId, Function.identity()));
        return sessions.stream().filter(session -> !session.isExpired(Instant.now())).map(session -> {
            DeviceEntity device = devices.get(session.getDeviceId());
            return new SessionResponse(session.getId(), session.getDeviceId(), device == null ? "Unknown" : device.getName(),
                    device == null ? null : device.getUserAgent(), device == null ? null : device.getLastIp(),
                    session.getCreatedAt(), session.getLastUsedAt(), session.getRefreshTokenExpiresAt());
        }).toList();
    }

    @Transactional
    public void revokeSession(UUID userId, UUID sessionId, RequestContext context) {
        AuthSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(ResourceNotFoundException::new);
        if (session.getRevokedAt() == null) {
            session.revoke("USER_REVOKED", Instant.now());
            sessionRepository.save(session);
            auditService.record("SESSION_REVOKED", userId, session.getDeviceId(), sessionId, context);
        }
    }

    @Transactional
    public DeviceResponse registerDevice(UUID userId, DeviceRegistrationRequest request, RequestContext context) {
        DeviceEntity device = new DeviceEntity(UUID.randomUUID(), userId, request.deviceName(), context.userAgent(),
                context.ipAddress(), Instant.now());
        deviceRepository.save(device);
        auditService.record("DEVICE_REGISTERED", userId, device.getId(), null, context);
        return new DeviceResponse(device.getId(), device.getName(), device.getUserAgent(), device.getLastIp(),
                device.getCreatedAt(), device.getLastSeenAt());
    }

    private DeviceEntity createDevice(UUID userId, UUID deviceId, String deviceName, RequestContext context) {
        Instant now = Instant.now();
        if (deviceId != null) {
            DeviceEntity existing = deviceRepository.findByIdAndUserId(deviceId, userId)
                    .orElseThrow(() -> new AuthException("Invalid device"));
            if (existing.getRevokedAt() != null) {
                throw new AuthException("Invalid device");
            }
            existing.touch(context.userAgent(), context.ipAddress(), now);
            return deviceRepository.save(existing);
        }
        String safeName = deviceName == null || deviceName.isBlank() ? "Unnamed device" : deviceName.trim();
        return deviceRepository.save(new DeviceEntity(UUID.randomUUID(), userId, safeName, context.userAgent(),
                context.ipAddress(), now));
    }

    private IssuedTokens createSession(UserEntity user, DeviceEntity device, RequestContext context, Instant now) {
        String rawRefreshToken = tokenService.newRefreshToken();
        AuthSessionEntity session = new AuthSessionEntity(UUID.randomUUID(), user.getId(), device.getId(),
                tokenHashingService.hash(rawRefreshToken), now.plus(properties.getJwt().getRefreshTokenTtl()), now);
        sessionRepository.save(session);
        String accessToken = tokenService.accessToken(user.getId(), session.getId(), device.getId(), now);
        return new IssuedTokens(accessToken, rawRefreshToken, properties.getJwt().getRefreshTokenTtl(), session.getId());
    }

    private void revokeReplayLineage(AuthSessionEntity replayed, Instant now, RequestContext context) {
        if (replayed.getReplacedBySessionId() != null) {
            sessionRepository.findById(replayed.getReplacedBySessionId()).ifPresent(replacement -> {
                if (replacement.getRevokedAt() == null) {
                    replacement.revoke("REFRESH_REPLAY", now);
                    sessionRepository.save(replacement);
                }
            });
        }
        deviceRepository.findById(replayed.getDeviceId()).ifPresent(device -> {
            device.revoke(now);
            deviceRepository.save(device);
        });
        auditService.record("REFRESH_REPLAY_DETECTED", replayed.getUserId(), replayed.getDeviceId(), replayed.getId(), context);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
