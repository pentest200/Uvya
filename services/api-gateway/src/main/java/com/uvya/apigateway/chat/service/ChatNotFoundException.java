package com.uvya.apigateway.chat.service;

public class ChatNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ChatNotFoundException() {
        super("Chat not found");
    }
}
