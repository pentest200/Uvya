package com.uvya.apigateway.chat.web;

import java.util.UUID;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
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
import com.uvya.apigateway.chat.service.ChatApplicationService;

@RestController
@RequestMapping("/v1/chats")
public class ChatController {
    private final ChatApplicationService chatService;

    public ChatController(ChatApplicationService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> create(@Valid @RequestBody CreateChatRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatService.create(context(jwt, httpRequest), request,
                idempotencyKey));
    }

    @GetMapping
    public ChatPageResponse<ChatResponse> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        return ChatPageResponse.from(chatService.list(context(jwt, httpRequest), page, size));
    }

    @GetMapping("/{chatId}")
    public ChatResponse get(@PathVariable UUID chatId, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        return chatService.get(context(jwt, httpRequest), chatId);
    }

    @PatchMapping("/{chatId}")
    public ChatResponse update(@PathVariable UUID chatId, @Valid @RequestBody PatchChatRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return chatService.update(context(jwt, httpRequest), chatId, request);
    }

    @PostMapping("/{chatId}/members")
    public ResponseEntity<Void> addMember(@PathVariable UUID chatId, @Valid @RequestBody AddChatMemberRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        chatService.addMember(context(jwt, httpRequest), chatId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @DeleteMapping("/{chatId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID chatId, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        chatService.removeMember(context(jwt, httpRequest), chatId, userId, idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{chatId}/leave")
    public ResponseEntity<Void> leave(@PathVariable UUID chatId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        chatService.leave(context(jwt, httpRequest), chatId, idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{chatId}/members")
    public ChatPageResponse<ChatMemberResponse> members(@PathVariable UUID chatId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        Page<ChatMemberResponse> result = chatService.members(context(jwt, httpRequest), chatId, page, size);
        return ChatPageResponse.from(result);
    }

    @GetMapping("/{chatId}/realtime-members")
    public Map<String, Object> realtimeMembers(@PathVariable UUID chatId, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        return Map.of("userIds", chatService.realtimeMembers(context(jwt, httpRequest), chatId));
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
