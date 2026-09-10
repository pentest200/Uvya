package com.uvya.apigateway.chat.service;

public class ChatConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ChatConflictException(String message) {
        super(message);
    }
}
