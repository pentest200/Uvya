package com.uvya.apigateway.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.uvya.apigateway.auth.config.AuthProperties;

@Service
public class AuthRateLimiter {
    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    public AuthRateLimiter(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void checkLogin(String ipAddress, String normalizedEmail) {
        try {
            if (count("auth:login:ip:", ipAddress) >= properties.getRateLimit().getLoginIpLimit()
                    || count("auth:login:identity:", normalizedEmail)
                    >= properties.getRateLimit().getLoginIdentityLimit()
                    || failureCount(ipAddress) >= properties.getRateLimit().getLoginIpLimit()) {
                throw new RateLimitExceededException();
            }
        } catch (DataAccessException exception) {
            throw new RateLimitExceededException();
        }
    }

    public void recordFailure(String ipAddress, String normalizedEmail) {
        increment("auth:login:fail:ip:", ipAddress);
        increment("auth:login:fail:identity:", normalizedEmail);
    }

    public void clearFailures(String ipAddress, String normalizedEmail) {
        try {
            redis.delete(key("auth:login:fail:ip:", ipAddress));
            redis.delete(key("auth:login:fail:identity:", normalizedEmail));
        } catch (DataAccessException ignored) {
            // Authentication success remains valid if a temporary failure counter cannot be cleared.
        }
    }

    private long count(String prefix, String value) {
        Long count = redis.opsForValue().increment(key(prefix, value), 1L);
        redis.expire(key(prefix, value), properties.getRateLimit().getWindow());
        return count == null ? Long.MAX_VALUE : count;
    }

    private void increment(String prefix, String value) {
        try {
            count(prefix, value);
        } catch (DataAccessException ignored) {
            // The request path remains protected by the identity lock in PostgreSQL.
        }
    }

    private long failureCount(String ipAddress) {
        String value = redis.opsForValue().get(key("auth:login:fail:ip:", ipAddress));
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return Long.MAX_VALUE;
        }
    }

    private String key(String prefix, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create rate-limit key", exception);
        }
    }
}
