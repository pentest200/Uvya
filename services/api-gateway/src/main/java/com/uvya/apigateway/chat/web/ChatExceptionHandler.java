package com.uvya.apigateway.chat.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.uvya.apigateway.chat.service.ChatAuthorizationException;
import com.uvya.apigateway.chat.service.ChatConflictException;
import com.uvya.apigateway.chat.service.ChatNotFoundException;
import com.uvya.apigateway.chat.service.ChatValidationException;

@RestControllerAdvice
public class ChatExceptionHandler {
    @ExceptionHandler(ChatNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ChatNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(ChatAuthorizationException.class)
    ResponseEntity<Map<String, String>> forbidden(ChatAuthorizationException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(ChatConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ChatConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(ChatValidationException.class)
    ResponseEntity<Map<String, String>> invalid(ChatValidationException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
