package com.uvya.apigateway.data.event;

public enum EventType {
    MESSAGE_CREATED("message.created"),
    MESSAGE_EDITED("message.edited"),
    MESSAGE_DELETED("message.deleted"),
    MESSAGE_DELIVERED("message.delivered"),
    MESSAGE_READ("message.read"),
    MESSAGE_REACTION_ADDED("message.reaction.added"),
    MESSAGE_REACTION_REMOVED("message.reaction.removed"),
    CHAT_CREATED("chat.created"),
    GROUP_MEMBER_ADDED("group.member.added"),
    GROUP_MEMBER_REMOVED("group.member.removed"),
    NOTIFICATION_REQUESTED("notification.requested");

    private final String value;

    EventType(String value) { this.value = value; }

    public String value() { return value; }
}
