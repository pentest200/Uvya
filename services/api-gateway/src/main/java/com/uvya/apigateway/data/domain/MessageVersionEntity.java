package com.uvya.apigateway.data.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "message_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_message_versions_message_version",
                columnNames = {"message_id", "version"})
})
public class MessageVersionEntity {
    @Id
    private UUID id;
    @Column(name = "message_id", nullable = false)
    private UUID messageId;
    @Column(nullable = false)
    private int version;
    @Column(name = "message_type", nullable = false, length = 32)
    private String messageType;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;
    @Column(name = "edited_by", nullable = false)
    private UUID editedBy;
    @Column(name = "edited_at", nullable = false)
    private Instant editedAt;

    protected MessageVersionEntity() { }

    public MessageVersionEntity(UUID id, UUID messageId, int version, String messageType,
            String body, UUID editedBy, Instant editedAt) {
        this.id = id;
        this.messageId = messageId;
        this.version = version;
        this.messageType = messageType;
        this.body = body;
        this.editedBy = editedBy;
        this.editedAt = editedAt;
    }

    public UUID getId() { return id; }
    public UUID getMessageId() { return messageId; }
    public int getVersion() { return version; }
    public String getMessageType() { return messageType; }
    public String getBody() { return body; }
    public UUID getEditedBy() { return editedBy; }
    public Instant getEditedAt() { return editedAt; }
}
