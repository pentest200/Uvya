package com.uvya.apigateway.auth.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.uvya.apigateway.auth.service.AuthException;
import com.uvya.apigateway.auth.service.RateLimitExceededException;
import com.uvya.apigateway.auth.service.ResourceNotFoundException;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, String>> authenticationFailure(AuthException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<Map<String, String>> rateLimit(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }
}
