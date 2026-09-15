package com.uvya.apigateway.chat.web;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uvya.apigateway.chat.service.ChatAccessContext;
import com.uvya.apigateway.chat.service.ChatPinService;
import com.uvya.apigateway.chat.service.PinnedMessageResponse;

@RestController
@RequestMapping("/v1/chats")
public class ChatPinController {
    private final ChatPinService pinService;

    public ChatPinController(ChatPinService pinService) {
        this.pinService = pinService;
    }

    @PutMapping("/{chatId}/messages/{messageId}/pin")
    public PinnedMessageResponse pin(@PathVariable UUID chatId, @PathVariable UUID messageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return pinService.pin(context(jwt, request), chatId, messageId, idempotencyKey);
    }

    @DeleteMapping("/{chatId}/messages/{messageId}/pin")
    public ResponseEntity<Void> unpin(@PathVariable UUID chatId, @PathVariable UUID messageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        pinService.unpin(context(jwt, request), chatId, messageId, idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{chatId}/pinned-messages")
    public List<PinnedMessageResponse> list(@PathVariable UUID chatId, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return pinService.list(context(jwt, request), chatId);
    }

    private ChatAccessContext context(Jwt jwt, HttpServletRequest request) {
        try {
            return new ChatAccessContext(UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("did")), UUID.fromString(jwt.getClaimAsString("sid")),
                    request.getHeader("X-Request-ID"));
        } catch (RuntimeException exception) {
            throw new com.uvya.apigateway.chat.service.ChatAuthorizationException("Invalid authorization context");
        }
    }
}
