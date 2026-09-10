package com.uvya.apigateway.chat.service;

public class ChatValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ChatValidationException(String message) {
        super(message);
    }
}
