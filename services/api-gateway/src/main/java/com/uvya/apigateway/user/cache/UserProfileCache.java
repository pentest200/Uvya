package com.uvya.apigateway.user.cache;

import java.util.Optional;
import java.util.UUID;

import com.uvya.apigateway.user.web.UserProfileResponse;

public interface UserProfileCache {
    Optional<UserProfileResponse> get(UUID userId);

    void put(UserProfileResponse profile);

    void evict(UUID userId);
}
