package com.uvya.apigateway.auth.web;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 12, max = 128) String password,
        @Size(max = 120) String deviceName,
        UUID deviceId) {
    @Override
    public String toString() {
        return "RegisterRequest[email=[REDACTED], password=[REDACTED], deviceName=" + deviceName
                + ", deviceId=" + deviceId + "]";
    }
}
