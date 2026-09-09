package com.uvya.apigateway.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceRegistrationRequest(
        @NotBlank @Size(max = 120) String deviceName) {
}
