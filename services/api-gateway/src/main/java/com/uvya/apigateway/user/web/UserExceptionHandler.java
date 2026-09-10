package com.uvya.apigateway.user.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.uvya.apigateway.user.service.InvalidContactException;
import com.uvya.apigateway.user.service.UserNotFoundException;
import com.uvya.apigateway.user.service.UserServiceException;
import com.uvya.apigateway.user.service.UsernameTakenException;

@RestControllerAdvice
public class UserExceptionHandler {
    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(UsernameTakenException.class)
    ResponseEntity<Map<String, String>> usernameTaken(UsernameTakenException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler({InvalidContactException.class, UserServiceException.class})
    ResponseEntity<Map<String, String>> invalidUserRequest(UserServiceException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
