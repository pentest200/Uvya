package com.uvya.apigateway.message.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uvya.apigateway.chat.service.ChatAccessContext;
import com.uvya.apigateway.message.service.MessageApplicationService;

@RestController
@RequestMapping("/v1/chats/{chatId}/messages")
public class MessageController {
    private final MessageApplicationService messageService;

    public MessageController(MessageApplicationService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    public ResponseEntity<MessageResponse> send(@PathVariable UUID chatId,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(messageService.send(context(jwt, httpRequest), chatId,
                request, idempotencyKey));
    }

    @GetMapping
    public MessageHistoryResponse history(@PathVariable UUID chatId,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return messageService.history(context(jwt, httpRequest), chatId, before, size);
    }

    @PatchMapping("/{messageId}")
    public MessageResponse edit(@PathVariable UUID chatId, @PathVariable UUID messageId,
            @Valid @RequestBody PatchMessageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return messageService.edit(context(jwt, httpRequest), chatId, messageId, request, idempotencyKey);
    }

    @DeleteMapping("/{messageId}")
    public MessageResponse delete(@PathVariable UUID chatId, @PathVariable UUID messageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return messageService.delete(context(jwt, httpRequest), chatId, messageId, idempotencyKey);
    }

    private ChatAccessContext context(Jwt jwt, HttpServletRequest request) {
        try {
            return new ChatAccessContext(UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("did")), UUID.fromString(jwt.getClaimAsString("sid")),
                    request.getHeader("X-Request-ID"));
        } catch (RuntimeException exception) {
            throw new com.uvya.apigateway.message.service.MessageAuthorizationException(
                    "Invalid authorization context");
        }
    }
}
