package com.uvya.apigateway.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventTopicNamesTest {
    @Test
    void provisionsEveryDurableTopicWithItsRetryAndDeadLetterCompanions() {
        EventTopicNames names = new EventTopicNames();

        assertThat(names.baseTopics()).containsExactly("message.created", "message.edited", "message.deleted",
                "message.delivered", "message.read", "message.reaction.added", "message.reaction.removed",
                "chat.created", "group.member.added", "group.member.removed", "notification.requested",
                "search.index.requested", "moderation.reported", "analytics.event");
        assertThat(names.allProvisionedTopics()).contains("message.created.retry.immediate",
                "message.created.retry.delayed", "message.created.dlt");
        assertThat(names.consumedTopics()).doesNotContain("message.created.dlt");
    }

    @Test
    void retryTopicResolutionRetainsTheBaseEventTopic() {
        EventTopicNames names = new EventTopicNames();

        assertThat(names.baseTopic("message.created.retry.immediate")).isEqualTo("message.created");
        assertThat(names.baseTopic("message.created.retry.delayed")).isEqualTo("message.created");
        assertThat(names.baseTopic("message.created.dlt")).isEqualTo("message.created");
    }
}
