package com.uvya.apigateway.chat.web;

import java.time.Instant;
import java.util.UUID;

import com.uvya.apigateway.data.domain.ChatMemberRole;

public record ChatMemberResponse(UUID userId, ChatMemberRole role, Instant joinedAt, Instant mutedUntil,
        Instant archivedAt) {
}
