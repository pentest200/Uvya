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
@Table(name = "user_inbox")
public class UserInboxEntity {
    @EmbeddedId
    private UserInboxId id;
    @Column(name = "chat_id", nullable = false)
    private UUID chatId;
    @Column(nullable = false)
    private long sequence;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_state", nullable = false, length = 16)
    private DeliveryState deliveryState;
    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;
    @Column(name = "last_delivery_attempt_at")
    private Instant lastDeliveryAttemptAt;

    protected UserInboxEntity() { }

    public UserInboxEntity(UUID userId, UUID messageId, UUID chatId, long sequence, Instant receivedAt) {
        id = new UserInboxId(userId, messageId);
        this.chatId = chatId;
        this.sequence = sequence;
        this.receivedAt = receivedAt;
        this.deliveryState = DeliveryState.PERSISTED;
    }

    public UserInboxId getId() { return id; }
    public UUID getUserId() { return id.getUserId(); }
    public UUID getMessageId() { return id.getMessageId(); }
    public UUID getChatId() { return chatId; }
    public long getSequence() { return sequence; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public DeliveryState getDeliveryState() { return deliveryState; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public Instant getLastDeliveryAttemptAt() { return lastDeliveryAttemptAt; }

    public boolean markPending(Instant now) {
        if (deliveryState == DeliveryState.DELIVERED || deliveryState == DeliveryState.READ) {
            return false;
        }
        deliveryState = DeliveryState.PENDING;
        deliveryAttempts++;
        lastDeliveryAttemptAt = now;
        return true;
    }

    public void markDelivered(Instant now) {
        if (deliveryState != DeliveryState.READ) {
            deliveryState = DeliveryState.DELIVERED;
        }
        if (deliveredAt == null) {
            deliveredAt = now;
        }
    }

    public void markRead(Instant now) {
        deliveryState = DeliveryState.READ;
        if (deliveredAt == null) {
            deliveredAt = now;
        }
    }

    public void markFailed() {
        if (deliveryState != DeliveryState.DELIVERED && deliveryState != DeliveryState.READ) {
            deliveryState = DeliveryState.FAILED;
        }
    }
}
