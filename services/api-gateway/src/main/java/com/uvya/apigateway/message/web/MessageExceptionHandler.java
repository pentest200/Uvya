package com.uvya.apigateway.message.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.uvya.apigateway.message.service.MessageAuthorizationException;
import com.uvya.apigateway.message.service.MessageConflictException;
import com.uvya.apigateway.message.service.MessageNotFoundException;
import com.uvya.apigateway.message.service.MessageValidationException;

@RestControllerAdvice
public class MessageExceptionHandler {
    @ExceptionHandler(MessageNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(MessageNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MessageAuthorizationException.class)
    ResponseEntity<Map<String, String>> forbidden(MessageAuthorizationException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MessageConflictException.class)
    ResponseEntity<Map<String, String>> conflict(MessageConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MessageValidationException.class)
    ResponseEntity<Map<String, String>> invalid(MessageValidationException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
