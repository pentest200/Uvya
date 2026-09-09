package com.uvya.apigateway.auth.service;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        super("Resource not found");
    }
}
