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

    public void leave(Instant now) { leftAt = now; }
}
