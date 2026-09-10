package com.uvya.apigateway.user.web;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.uvya.apigateway.user.domain.Discoverability;

public record UpdateUserProfileRequest(
        @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$") String username,
        @Size(max = 120) String displayName,
        @Size(max = 500) String bio,
        JsonNode avatarMetadata,
        JsonNode privacySettings,
        Discoverability discoverability) {
}
