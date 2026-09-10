package com.uvya.apigateway.message.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PatchMessageRequest(@NotBlank @Pattern(regexp = "(?i)text") String type,
        @NotBlank @Size(max = 16_000) String body, Integer expectedVersion) {
}
