package com.uvya.apigateway.message.service;

public class MessageAuthorizationException extends RuntimeException {
    public MessageAuthorizationException(String message) {
        super(message);
    }
}
