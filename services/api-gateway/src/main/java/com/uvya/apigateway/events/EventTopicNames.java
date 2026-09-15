package com.uvya.apigateway.events;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import com.uvya.apigateway.data.event.EventType;

@Component("eventTopicNames")
public class EventTopicNames {
    private final List<String> baseTopics = Arrays.stream(EventType.values()).map(EventType::value).toList();

    public String topicFor(String eventType) {
        if (!baseTopics.contains(eventType)) {
            throw new IllegalArgumentException("Unsupported event topic: " + eventType);
        }
        return eventType;
    }

    public String immediateRetry(String baseTopic) { return baseTopic + ".retry.immediate"; }

    public String delayedRetry(String baseTopic) { return baseTopic + ".retry.delayed"; }

    public String deadLetter(String baseTopic) { return baseTopic + ".dlt"; }

    public String baseTopic(String topic) {
        return baseTopics.stream().filter(base -> topic.equals(base) || topic.startsWith(base + "."))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported event topic: " + topic));
    }

    public String[] baseTopics() { return baseTopics.toArray(String[]::new); }

    public String[] consumedTopics() {
        return baseTopics.stream().flatMap(base -> java.util.stream.Stream.of(base, immediateRetry(base),
                delayedRetry(base))).toArray(String[]::new);
    }

    public String[] messageFanoutTopics() {
        return java.util.stream.Stream.of(EventType.MESSAGE_CREATED.value(),
                immediateRetry(EventType.MESSAGE_CREATED.value()), delayedRetry(EventType.MESSAGE_CREATED.value()),
                EventType.MESSAGE_DELIVERED.value(), immediateRetry(EventType.MESSAGE_DELIVERED.value()),
                delayedRetry(EventType.MESSAGE_DELIVERED.value()), EventType.MESSAGE_REACTION_ADDED.value(),
                immediateRetry(EventType.MESSAGE_REACTION_ADDED.value()), delayedRetry(EventType.MESSAGE_REACTION_ADDED.value()),
                EventType.MESSAGE_REACTION_REMOVED.value(), immediateRetry(EventType.MESSAGE_REACTION_REMOVED.value()),
                delayedRetry(EventType.MESSAGE_REACTION_REMOVED.value()), EventType.MESSAGE_PINNED.value(),
                immediateRetry(EventType.MESSAGE_PINNED.value()), delayedRetry(EventType.MESSAGE_PINNED.value()),
                EventType.MESSAGE_UNPINNED.value(), immediateRetry(EventType.MESSAGE_UNPINNED.value()),
                delayedRetry(EventType.MESSAGE_UNPINNED.value())).toArray(String[]::new);
    }

    public List<String> allProvisionedTopics() {
        return baseTopics.stream().flatMap(base -> java.util.stream.Stream.of(base, immediateRetry(base),
                delayedRetry(base), deadLetter(base))).toList();
    }
}
