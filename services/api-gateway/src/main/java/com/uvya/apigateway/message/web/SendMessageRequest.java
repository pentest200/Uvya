package com.uvya.apigateway.message.web;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(@NotNull UUID clientMessageId,
        @NotBlank @Pattern(regexp = "(?i)text") String type,
        @NotBlank @Size(max = 16_000) String body, UUID replyToMessageId,
        UUID forwardedFromMessageId, UUID threadRootMessageId, List<JsonNode> attachments) {
    public SendMessageRequest(UUID clientMessageId, String type, String body, UUID replyToMessageId,
            UUID forwardedFromMessageId, List<JsonNode> attachments) {
        this(clientMessageId, type, body, replyToMessageId, forwardedFromMessageId, null, attachments);
    }
}
