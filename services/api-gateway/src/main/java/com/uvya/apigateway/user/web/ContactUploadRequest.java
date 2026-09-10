package com.uvya.apigateway.user.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ContactUploadRequest(@NotEmpty @Size(max = 1000) List<@Valid ContactIdentifierRequest> contacts) {
}
