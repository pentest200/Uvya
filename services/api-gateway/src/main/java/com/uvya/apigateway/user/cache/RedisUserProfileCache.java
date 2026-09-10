package com.uvya.apigateway.user.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.uvya.apigateway.user.web.UserProfileResponse;

@Service
public class RedisUserProfileCache implements UserProfileCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisUserProfileCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "uvya:user-profile:v1:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisUserProfileCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<UserProfileResponse> get(UUID userId) {
        try {
            String value = redis.opsForValue().get(key(userId));
            return value == null ? Optional.empty() : Optional.of(objectMapper.readValue(value,
                    UserProfileResponse.class));
        } catch (RuntimeException | java.io.IOException exception) {
            LOGGER.debug("profile_cache_read_failed userId={}", userId);
            return Optional.empty();
        }
    }

    @Override
    public void put(UserProfileResponse profile) {
        try {
            redis.opsForValue().set(key(profile.userId()), objectMapper.writeValueAsString(profile), TTL);
        } catch (RuntimeException | java.io.IOException exception) {
            LOGGER.debug("profile_cache_write_failed userId={}", profile.userId());
        }
    }

    @Override
    public void evict(UUID userId) {
        try {
            redis.delete(key(userId));
        } catch (RuntimeException exception) {
            LOGGER.debug("profile_cache_evict_failed userId={}", userId);
        }
    }

    private String key(UUID userId) {
        return KEY_PREFIX + userId;
    }
}
