package com.uvya.apigateway.message.service;

public class MessageConflictException extends RuntimeException {
    public MessageConflictException(String message) {
        super(message);
    }
}
