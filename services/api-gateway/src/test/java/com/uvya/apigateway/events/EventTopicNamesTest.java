package com.uvya.apigateway.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventTopicNamesTest {
    @Test
    void provisionsEveryDurableTopicWithItsRetryAndDeadLetterCompanions() {
        EventTopicNames names = new EventTopicNames();

        assertThat(names.baseTopics()).containsExactly("message.created", "message.edited", "message.deleted",
                "message.delivered", "message.read", "message.reaction.added", "message.reaction.removed",
                "message.pinned", "message.unpinned",
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

    @Test
    void fanoutGroupConsumesCreatedAndDeliveredRetryStreams() {
        EventTopicNames names = new EventTopicNames();

        assertThat(names.messageFanoutTopics()).containsExactly("message.created", "message.created.retry.immediate",
                "message.created.retry.delayed", "message.delivered", "message.delivered.retry.immediate",
                "message.delivered.retry.delayed", "message.reaction.added", "message.reaction.added.retry.immediate",
                "message.reaction.added.retry.delayed", "message.reaction.removed",
                "message.reaction.removed.retry.immediate", "message.reaction.removed.retry.delayed",
                "message.pinned", "message.pinned.retry.immediate", "message.pinned.retry.delayed",
                "message.unpinned", "message.unpinned.retry.immediate", "message.unpinned.retry.delayed");
    }
}
