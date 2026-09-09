package com.uvya.apigateway.auth.service;

public record RequestContext(String ipAddress, String requestId, String userAgent) {
}
