package com.uvya.apigateway.user.service;

public class InvalidContactException extends UserServiceException {
    private static final long serialVersionUID = 1L;

    public InvalidContactException() {
        super("Invalid contact identifier");
    }
}
