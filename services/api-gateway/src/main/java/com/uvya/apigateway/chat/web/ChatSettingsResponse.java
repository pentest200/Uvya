package com.uvya.apigateway.chat.web;

public record ChatSettingsResponse(boolean memberPostingEnabled, boolean discoverable, int slowModeSeconds) {
}
