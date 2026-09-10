package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_members")
public class ChatMemberEntity {
    @EmbeddedId
    private ChatMemberId id;
    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false, length = 32)
    private ChatMemberRole role;
    @Column(nullable = false)
    private Instant joinedAt;
    private Instant leftAt;
    private Instant mutedUntil;
    private Instant archivedAt;
    private Instant bannedAt;
    private Instant bannedUntil;

    protected ChatMemberEntity() { }

    public ChatMemberEntity(UUID chatId, UUID userId, ChatMemberRole role, Instant joinedAt) {
        this.id = new ChatMemberId(chatId, userId);
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public ChatMemberId getId() { return id; }
    public UUID getChatId() { return id.getChatId(); }
    public UUID getUserId() { return id.getUserId(); }
    public ChatMemberRole getRole() { return role; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLeftAt() { return leftAt; }
    public Instant getMutedUntil() { return mutedUntil; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getBannedAt() { return bannedAt; }
    public Instant getBannedUntil() { return bannedUntil; }

    public void leave(Instant now) { leftAt = now; }

    public void setRole(ChatMemberRole newRole) { role = newRole; }

    public void muteUntil(Instant until) { mutedUntil = until; }

    public void archive(Instant now) { archivedAt = now; }

    public boolean isBanned(Instant now) {
        return bannedAt != null && (bannedUntil == null || bannedUntil.isAfter(now));
    }

    public void ban(Instant now, Instant until) {
        bannedAt = now;
        bannedUntil = until;
    }
}
