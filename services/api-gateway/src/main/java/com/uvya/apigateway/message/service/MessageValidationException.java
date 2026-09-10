package com.uvya.apigateway.message.service;

public class MessageValidationException extends RuntimeException {
    public MessageValidationException(String message) {
        super(message);
    }
}
