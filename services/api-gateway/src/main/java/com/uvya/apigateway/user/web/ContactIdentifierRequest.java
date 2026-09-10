package com.uvya.apigateway.user.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.uvya.apigateway.user.domain.ContactIdentifierType;

public record ContactIdentifierRequest(@NotNull ContactIdentifierType identifierType,
        @NotBlank @Size(max = 320) String identifier) {
}
