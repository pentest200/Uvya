package com.uvya.apigateway.chat.service;

public class ChatAuthorizationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ChatAuthorizationException(String message) {
        super(message);
    }
}
