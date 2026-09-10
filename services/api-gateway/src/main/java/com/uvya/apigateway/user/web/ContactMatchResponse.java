package com.uvya.apigateway.user.web;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record ContactMatchResponse(UUID userId, String username, String displayName,
        JsonNode avatarMetadata, boolean mutualContact) {
}
