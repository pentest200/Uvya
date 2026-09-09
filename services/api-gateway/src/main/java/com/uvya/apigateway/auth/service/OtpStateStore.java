package com.uvya.apigateway.auth.service;

import java.time.Duration;
import java.util.Optional;

public interface OtpStateStore {
    void putChallenge(String challengeId, String hashedCode, Duration ttl);
    Optional<String> getChallenge(String challengeId);
    void removeChallenge(String challengeId);
}
