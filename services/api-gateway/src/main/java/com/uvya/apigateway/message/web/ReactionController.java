package com.uvya.apigateway.message.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

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
import com.uvya.apigateway.data.service.DataFoundationException;
import com.uvya.apigateway.data.service.ReactionService;
import com.uvya.apigateway.data.service.ReactionState;
import com.uvya.apigateway.message.service.MessageAuthorizationException;
import com.uvya.apigateway.message.service.MessageNotFoundException;
import com.uvya.apigateway.message.service.MessageValidationException;

@RestController
@RequestMapping("/v1/messages/{messageId}/reactions")
public class ReactionController {
    private final ReactionService reactionService;

    public ReactionController(ReactionService reactionService) {
        this.reactionService = reactionService;
    }

    @PutMapping("/{emoji}")
    public ReactionState add(@PathVariable UUID messageId, @PathVariable String emoji,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        ChatAccessContext context = context(jwt, request);
        return execute(() -> reactionService.add(context.userId(), messageId, emoji, trace(context),
                operationKey(idempotencyKey)));
    }

    @DeleteMapping("/{emoji}")
    public ReactionState remove(@PathVariable UUID messageId, @PathVariable String emoji,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        ChatAccessContext context = context(jwt, request);
        return execute(() -> reactionService.remove(context.userId(), messageId, emoji, trace(context),
                operationKey(idempotencyKey)));
    }

    @GetMapping
    public ReactionState state(@PathVariable UUID messageId, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        ChatAccessContext context = context(jwt, request);
        return execute(() -> reactionService.state(context.userId(), messageId));
    }

    private ReactionState execute(java.util.function.Supplier<ReactionState> operation) {
        try {
            return operation.get();
        } catch (DataFoundationException exception) {
            String message = exception.getMessage() == null ? "Reaction request failed" : exception.getMessage();
            if (message.contains("not found")) {
                throw new MessageNotFoundException(message);
            }
            if (message.contains("member")) {
                throw new MessageAuthorizationException(message);
            }
            throw new MessageValidationException(message);
        }
    }

    private ChatAccessContext context(Jwt jwt, HttpServletRequest request) {
        try {
            return new ChatAccessContext(UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("did")), UUID.fromString(jwt.getClaimAsString("sid")),
                    request.getHeader("X-Request-ID"));
        } catch (RuntimeException exception) {
            throw new MessageAuthorizationException("Invalid authorization context");
        }
    }

    private String trace(ChatAccessContext context) {
        return context.traceId() == null || context.traceId().isBlank()
                ? UUID.randomUUID().toString() : context.traceId();
    }

    private String operationKey(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey;
    }
}
