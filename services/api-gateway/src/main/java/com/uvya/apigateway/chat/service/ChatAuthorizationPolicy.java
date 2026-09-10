package com.uvya.apigateway.chat.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uvya.apigateway.auth.domain.AuthSessionEntity;
import com.uvya.apigateway.auth.domain.DeviceEntity;
import com.uvya.apigateway.auth.domain.UserEntity;
import com.uvya.apigateway.auth.domain.UserStatus;
import com.uvya.apigateway.auth.repository.AuthSessionRepository;
import com.uvya.apigateway.auth.repository.DeviceRepository;
import com.uvya.apigateway.auth.repository.UserRepository;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.ChatMemberEntity;
import com.uvya.apigateway.data.domain.ChatMemberRole;
import com.uvya.apigateway.data.domain.ChatSettingsEntity;
import com.uvya.apigateway.data.domain.ChatType;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.ChatSettingsRepository;

@Service
public class ChatAuthorizationPolicy {
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final AuthSessionRepository sessionRepository;
    private final ChatMemberRepository memberRepository;
    private final ChatSettingsRepository settingsRepository;

    public ChatAuthorizationPolicy(UserRepository userRepository, DeviceRepository deviceRepository,
            AuthSessionRepository sessionRepository, ChatMemberRepository memberRepository,
            ChatSettingsRepository settingsRepository) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.memberRepository = memberRepository;
        this.settingsRepository = settingsRepository;
    }

    @Transactional(readOnly = true)
    public ChatMemberEntity authorize(ChatAccessContext context, ChatEntity chat, ChatAction action) {
        validateIdentity(context);
        ChatMemberEntity member = memberRepository.findById(new com.uvya.apigateway.data.domain.ChatMemberId(
                chat.getId(), context.userId())).orElse(null);
        if (member == null || member.getLeftAt() != null) {
            throw new ChatAuthorizationException("User is not a chat member");
        }
        if (member.isBanned(Instant.now())) {
            throw new ChatAuthorizationException("User is banned from this chat");
        }
        if (action == ChatAction.UPDATE && !isOwnerOrAdmin(member)) {
            throw new ChatAuthorizationException("Chat administration permission required");
        }
        if ((action == ChatAction.ADD_MEMBER || action == ChatAction.REMOVE_MEMBER)
                && !isOwnerOrAdmin(member)) {
            throw new ChatAuthorizationException("Membership administration permission required");
        }
        if (action == ChatAction.LEAVE && member.getRole() == ChatMemberRole.OWNER) {
            throw new ChatAuthorizationException("The owner must transfer ownership before leaving");
        }
        if (action == ChatAction.POST_MESSAGE && !canPost(chat, member)) {
            throw new ChatAuthorizationException("User cannot post in this chat");
        }
        return member;
    }

    @Transactional(readOnly = true)
    public void authorizeMemberRole(ChatAccessContext context, ChatEntity chat, ChatMemberRole requestedRole) {
        ChatMemberEntity actor = authorize(context, chat, ChatAction.ADD_MEMBER);
        if (requestedRole == null || requestedRole == ChatMemberRole.OWNER) {
            throw new ChatValidationException("Invalid requested member role");
        }
        if (actor.getRole() == ChatMemberRole.ADMIN && requestedRole == ChatMemberRole.ADMIN) {
            throw new ChatAuthorizationException("Only the owner can grant administrator role");
        }
        if (chat.getChatType() == ChatType.DIRECT && requestedRole != ChatMemberRole.MEMBER) {
            throw new ChatValidationException("Direct chats only support member role");
        }
    }

    @Transactional(readOnly = true)
    public void authorizeMemberRemoval(ChatAccessContext context, ChatEntity chat, ChatMemberEntity target) {
        ChatMemberEntity actor = authorize(context, chat, ChatAction.REMOVE_MEMBER);
        if (target.getRole() == ChatMemberRole.OWNER || target.getUserId().equals(context.userId())) {
            throw new ChatAuthorizationException("This member cannot be removed with this action");
        }
        if (actor.getRole() == ChatMemberRole.ADMIN && target.getRole() == ChatMemberRole.ADMIN) {
            throw new ChatAuthorizationException("Administrators cannot remove another administrator");
        }
    }

    public void validateIdentity(ChatAccessContext context) {
        if (context == null || context.userId() == null || context.deviceId() == null || context.sessionId() == null) {
            throw new ChatAuthorizationException("Invalid authorization context");
        }
        UserEntity user = userRepository.findById(context.userId()).orElseThrow(
                () -> new ChatAuthorizationException("User is not active"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ChatAuthorizationException("User is not active");
        }
        DeviceEntity device = deviceRepository.findByIdAndUserId(context.deviceId(), context.userId()).orElseThrow(
                () -> new ChatAuthorizationException("Device is not valid for this user"));
        if (device.getRevokedAt() != null) {
            throw new ChatAuthorizationException("Device is revoked");
        }
        AuthSessionEntity session = sessionRepository.findByIdAndUserId(context.sessionId(), context.userId())
                .orElseThrow(() -> new ChatAuthorizationException("Session is not valid for this user"));
        if (session.getRevokedAt() != null || session.isExpired(Instant.now())
                || !context.deviceId().equals(session.getDeviceId())) {
            throw new ChatAuthorizationException("Session is not active for this device");
        }
    }

    @Transactional(readOnly = true)
    public void authorizeInternalPost(UUID userId, UUID deviceId, ChatEntity chat) {
        UserEntity user = userRepository.findById(userId).orElseThrow(
                () -> new ChatAuthorizationException("User is not active"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ChatAuthorizationException("User is not active");
        }
        DeviceEntity device = deviceRepository.findByIdAndUserId(deviceId, userId).orElseThrow(
                () -> new ChatAuthorizationException("Device is not valid for this user"));
        if (device.getRevokedAt() != null) {
            throw new ChatAuthorizationException("Device is revoked");
        }
        ChatMemberEntity member = memberRepository.findById(new com.uvya.apigateway.data.domain.ChatMemberId(
                chat.getId(), userId)).orElseThrow(() -> new ChatAuthorizationException("User is not a chat member"));
        if (member.getLeftAt() != null || member.isBanned(Instant.now()) || !canPost(chat, member)) {
            throw new ChatAuthorizationException("User cannot post in this chat");
        }
    }

    private boolean isOwnerOrAdmin(ChatMemberEntity member) {
        return member.getRole() == ChatMemberRole.OWNER || member.getRole() == ChatMemberRole.ADMIN;
    }

    private boolean canPost(ChatEntity chat, ChatMemberEntity member) {
        if (member.getRole() == ChatMemberRole.RESTRICTED) {
            return false;
        }
        if (chat.getChatType() != ChatType.CHANNEL) {
            return true;
        }
        ChatSettingsEntity settings = settingsRepository.findByChatId(chat.getId()).orElse(null);
        return member.getRole() == ChatMemberRole.OWNER || member.getRole() == ChatMemberRole.ADMIN
                || member.getRole() == ChatMemberRole.MODERATOR
                || (settings != null && settings.isMemberPostingEnabled());
    }
}
