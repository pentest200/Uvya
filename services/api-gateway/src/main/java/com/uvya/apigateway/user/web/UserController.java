package com.uvya.apigateway.user.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uvya.apigateway.auth.service.RequestContext;
import com.uvya.apigateway.user.service.UserService;

@RestController
@RequestMapping("/v1/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getMe(userId(jwt));
    }

    @PatchMapping("/me")
    public UserProfileResponse updateMe(@Valid @RequestBody UpdateUserProfileRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return userService.updateMe(userId(jwt), request, context(httpRequest));
    }

    @GetMapping("/search")
    public PagedResponse<UserProfileResponse> search(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        Page<UserProfileResponse> result = userService.search(userId(jwt), q, page, size);
        return PagedResponse.from(result);
    }

    @GetMapping("/{userId}")
    public UserProfileResponse profile(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return userService.getPublic(userId(jwt), userId);
    }

    @PostMapping("/contacts")
    public ContactUploadResponse uploadContacts(@Valid @RequestBody ContactUploadRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return userService.uploadContacts(userId(jwt), request, context(httpRequest));
    }

    @PostMapping("/{userId}/block")
    public ResponseEntity<Void> block(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        userService.block(userId(jwt), userId, context(httpRequest));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @DeleteMapping("/{userId}/block")
    public ResponseEntity<Void> unblock(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        userService.unblock(userId(jwt), userId, context(httpRequest));
        return ResponseEntity.noContent().build();
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("X-Request-ID"),
                request.getHeader(HttpHeaders.USER_AGENT));
    }
}
