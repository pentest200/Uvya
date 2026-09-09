package com.uvya.apigateway.auth.service;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisOtpStateStore implements OtpStateStore {
    private final StringRedisTemplate redis;

    public RedisOtpStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void putChallenge(String challengeId, String hashedCode, Duration ttl) {
        redis.opsForValue().set("auth:otp:" + challengeId, hashedCode, ttl);
    }

    @Override
    public Optional<String> getChallenge(String challengeId) {
        return Optional.ofNullable(redis.opsForValue().get("auth:otp:" + challengeId));
    }

    @Override
    public void removeChallenge(String challengeId) {
        redis.delete("auth:otp:" + challengeId);
    }
}
