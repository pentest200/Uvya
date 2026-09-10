package com.uvya.apigateway.chat.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uvya.apigateway.data.domain.ChatMemberRole;
import com.uvya.apigateway.data.domain.ChatType;

public record ChatResponse(UUID chatId, ChatType chatType, String title, UUID createdBy, Instant createdAt,
        Instant updatedAt, ChatMemberRole viewerRole, long memberCount, ChatSettingsResponse settings,
        List<UUID> pinnedMessageIds) {
}
