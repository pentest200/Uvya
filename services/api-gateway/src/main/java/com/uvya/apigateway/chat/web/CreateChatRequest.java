package com.uvya.apigateway.chat.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.uvya.apigateway.data.domain.ChatType;

public record CreateChatRequest(@NotNull ChatType chatType, @Size(max = 255) String title,
        @Size(max = 100) List<UUID> memberIds, ChatSettingsRequest settings) {
}
