package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_settings")
public class ChatSettingsEntity {
    @Id
    @Column(name = "chat_id")
    private UUID chatId;
    @Column(name = "member_posting_enabled", nullable = false)
    private boolean memberPostingEnabled;
    @Column(nullable = false)
    private boolean discoverable;
    @Column(name = "slow_mode_seconds", nullable = false)
    private int slowModeSeconds;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected ChatSettingsEntity() { }

    public ChatSettingsEntity(UUID chatId, boolean memberPostingEnabled, boolean discoverable,
            int slowModeSeconds, Instant now) {
        this.chatId = chatId;
        this.memberPostingEnabled = memberPostingEnabled;
        this.discoverable = discoverable;
        this.slowModeSeconds = slowModeSeconds;
        createdAt = now;
        updatedAt = now;
    }

    public UUID getChatId() { return chatId; }
    public boolean isMemberPostingEnabled() { return memberPostingEnabled; }
    public boolean isDiscoverable() { return discoverable; }
    public int getSlowModeSeconds() { return slowModeSeconds; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(Boolean memberPostingEnabled, Boolean discoverable, Integer slowModeSeconds, Instant now) {
        if (memberPostingEnabled != null) {
            this.memberPostingEnabled = memberPostingEnabled;
        }
        if (discoverable != null) {
            this.discoverable = discoverable;
        }
        if (slowModeSeconds != null) {
            if (slowModeSeconds < 0) {
                throw new IllegalArgumentException("slowModeSeconds cannot be negative");
            }
            this.slowModeSeconds = slowModeSeconds;
        }
        updatedAt = now;
    }
}
