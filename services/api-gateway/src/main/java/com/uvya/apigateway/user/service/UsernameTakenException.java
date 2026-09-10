package com.uvya.apigateway.user.service;

public class UsernameTakenException extends UserServiceException {
    private static final long serialVersionUID = 1L;

    public UsernameTakenException() {
        super("Username unavailable");
    }
}
