package com.uvya.apigateway.chat.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ChatSettingsRequest(Boolean memberPostingEnabled, Boolean discoverable,
        @Min(0) @Max(86400) Integer slowModeSeconds) {
}
