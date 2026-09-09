package com.uvya.apigateway.auth.service;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException() {
        super("Too many authentication attempts");
    }
}
