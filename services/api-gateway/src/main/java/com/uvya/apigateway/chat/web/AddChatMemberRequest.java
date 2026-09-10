package com.uvya.apigateway.chat.web;

import jakarta.validation.constraints.NotNull;

import com.uvya.apigateway.data.domain.ChatMemberRole;

public record AddChatMemberRequest(@NotNull java.util.UUID userId, @NotNull ChatMemberRole role) {
}
