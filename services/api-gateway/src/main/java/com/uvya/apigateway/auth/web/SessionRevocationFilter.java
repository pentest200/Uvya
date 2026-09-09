package com.uvya.apigateway.auth.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.uvya.apigateway.auth.domain.AuthSessionEntity;
import com.uvya.apigateway.auth.repository.AuthSessionRepository;

public class SessionRevocationFilter extends OncePerRequestFilter {
    private final AuthSessionRepository sessionRepository;

    public SessionRevocationFilter(AuthSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt jwt
                && !isSessionActive(jwt)) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Session is no longer active\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSessionActive(Jwt jwt) {
        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            UUID sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
            AuthSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId).orElse(null);
            return session != null && session.getRevokedAt() == null && !session.isExpired(java.time.Instant.now());
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
