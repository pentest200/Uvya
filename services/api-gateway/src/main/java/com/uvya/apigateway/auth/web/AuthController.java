package com.uvya.apigateway.auth.web;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uvya.apigateway.auth.config.AuthProperties;
import com.uvya.apigateway.auth.service.AuthService;
import com.uvya.apigateway.auth.service.IssuedTokens;
import com.uvya.apigateway.auth.service.RequestContext;

import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthProperties properties;

    public AuthController(AuthService authService, AuthProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return issuedResponse(authService.register(request, context(httpRequest)), httpResponse, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return issuedResponse(authService.login(request, context(httpRequest)), httpResponse, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody(required = false) RefreshRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = HttpHeaders.COOKIE, required = false)
            String cookieHeader,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String refreshToken = request != null && request.refreshToken() != null
                ? request.refreshToken() : readCookie(cookieHeader);
        return issuedResponse(authService.refresh(refreshToken, context(httpRequest)), httpResponse, HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) RefreshRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = HttpHeaders.COOKIE,
            required = false) String cookieHeader,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = jwt == null ? null : UUID.fromString(jwt.getSubject());
        String refreshToken = request != null && request.refreshToken() != null
                ? request.refreshToken() : readCookie(cookieHeader);
        authService.logout(refreshToken, userId, context(httpRequest));
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@AuthenticationPrincipal Jwt jwt) {
        return authService.sessions(UUID.fromString(jwt.getSubject()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID sessionId, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        authService.revokeSession(UUID.fromString(jwt.getSubject()), sessionId, context(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices")
    public ResponseEntity<DeviceResponse> registerDevice(@Valid @RequestBody DeviceRegistrationRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.registerDevice(
                UUID.fromString(jwt.getSubject()), request, context(httpRequest)));
    }

    private ResponseEntity<AuthResponse> issuedResponse(IssuedTokens tokens, HttpServletResponse response,
            HttpStatus status) {
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), tokens.refreshTokenTtl()).toString());
        AuthResponse body = new AuthResponse(tokens.accessToken(), "Bearer",
                properties.getJwt().getAccessTokenTtl().toSeconds(), tokens.sessionId());
        return ResponseEntity.status(status).body(body);
    }

    private ResponseCookie refreshCookie(String token, java.time.Duration ttl) {
        return ResponseCookie.from(properties.getRefreshCookie().getName(), token)
                .httpOnly(true)
                .secure(properties.getRefreshCookie().isSecure())
                .sameSite(properties.getRefreshCookie().getSameSite())
                .path("/v1/auth")
                .maxAge(ttl)
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(properties.getRefreshCookie().getName(), "")
                .httpOnly(true)
                .secure(properties.getRefreshCookie().isSecure())
                .sameSite(properties.getRefreshCookie().getSameSite())
                .path("/v1/auth")
                .maxAge(java.time.Duration.ZERO)
                .build();
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("X-Request-ID"),
                request.getHeader(HttpHeaders.USER_AGENT));
    }

    private String readCookie(String cookieHeader) {
        if (cookieHeader == null) {
            return null;
        }
        String prefix = properties.getRefreshCookie().getName() + "=";
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length());
            }
        }
        return null;
    }
}
