package com.uvya.apigateway.user.web;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import com.uvya.apigateway.auth.domain.UserStatus;
import com.uvya.apigateway.user.domain.Discoverability;

public record UserProfileResponse(UUID userId, String email, String username, String displayName, String bio,
        JsonNode avatarMetadata, JsonNode privacySettings, UserStatus accountStatus,
        Discoverability discoverability) {
}
