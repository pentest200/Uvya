package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chats")
public class ChatEntity {
    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "chat_type", nullable = false, length = 16)
    private ChatType chatType;
    @Column(length = 255)
    private String title;
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
    @Column(name = "last_message_sequence", nullable = false)
    private long lastMessageSequence;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected ChatEntity() { }

    public ChatEntity(UUID id, ChatType chatType, String title, UUID createdBy, Instant now) {
        this.id = id;
        this.chatType = chatType;
        this.title = title;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public ChatType getChatType() { return chatType; }
    public String getTitle() { return title; }
    public UUID getCreatedBy() { return createdBy; }
    public long getLastMessageSequence() { return lastMessageSequence; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public long nextMessageSequence(Instant now) {
        lastMessageSequence++;
        updatedAt = now;
        return lastMessageSequence;
    }
}
