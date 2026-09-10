package com.uvya.apigateway.chat.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record PatchChatRequest(@Size(max = 255) String title, @Valid ChatSettingsRequest settings) {
}
