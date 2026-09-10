package com.uvya.apigateway.data.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Embeddable;

@Embeddable
public class BlockedUserId implements Serializable {
    private UUID blockerUserId;
    private UUID blockedUserId;

    protected BlockedUserId() { }

    public BlockedUserId(UUID blockerUserId, UUID blockedUserId) {
        this.blockerUserId = blockerUserId;
        this.blockedUserId = blockedUserId;
    }

    public UUID getBlockerUserId() { return blockerUserId; }
    public UUID getBlockedUserId() { return blockedUserId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlockedUserId that)) {
            return false;
        }
        return Objects.equals(blockerUserId, that.blockerUserId)
                && Objects.equals(blockedUserId, that.blockedUserId);
    }

    @Override
    public int hashCode() { return Objects.hash(blockerUserId, blockedUserId); }
}
