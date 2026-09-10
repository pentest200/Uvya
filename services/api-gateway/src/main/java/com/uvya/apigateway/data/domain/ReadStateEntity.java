package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "read_states")
public class ReadStateEntity {
    @EmbeddedId
    private ReadStateId id;
    @Column(name = "last_read_sequence", nullable = false)
    private long lastReadSequence;
    @Column(nullable = false)
    private Instant updatedAt;

    protected ReadStateEntity() { }

    public ReadStateEntity(UUID chatId, UUID userId, long lastReadSequence, Instant updatedAt) {
        id = new ReadStateId(chatId, userId);
        this.lastReadSequence = lastReadSequence;
        this.updatedAt = updatedAt;
    }

    public ReadStateId getId() { return id; }
    public UUID getChatId() { return id.getChatId(); }
    public UUID getUserId() { return id.getUserId(); }
    public long getLastReadSequence() { return lastReadSequence; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void advanceTo(long sequence, Instant now) {
        if (sequence > lastReadSequence) {
            lastReadSequence = sequence;
            updatedAt = now;
        }
    }
}
