package com.uvya.apigateway.user.service;

public class UserNotFoundException extends UserServiceException {
    private static final long serialVersionUID = 1L;

    public UserNotFoundException() {
        super("User not found");
    }
}
