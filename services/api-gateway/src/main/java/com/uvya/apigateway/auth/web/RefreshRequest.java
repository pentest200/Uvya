package com.uvya.apigateway.auth.web;

import jakarta.validation.constraints.Size;

public record RefreshRequest(@Size(max = 1024) String refreshToken) {
    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=[REDACTED]]";
    }
}
