package com.uvya.apigateway.events;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.uvya.apigateway.events.config.EventProperties;

@Component
@ConditionalOnProperty(prefix = "uvya.events.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaRetryPublisher {
    public static final String ATTEMPT_HEADER = "uvya-retry-attempt";
    public static final String NOT_BEFORE_HEADER = "uvya-retry-not-before";
    public static final String ERROR_HEADER = "uvya-retry-error";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventEnvelopeCodec codec;
    private final EventTopicNames topicNames;
    private final EventPartitionKeyResolver keyResolver;
    private final EventRetryPolicy retryPolicy;
    private final EventMetrics metrics;
    private final EventProperties properties;

    public KafkaRetryPublisher(@Qualifier("eventKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            EventEnvelopeCodec codec,
            EventTopicNames topicNames, EventPartitionKeyResolver keyResolver, EventRetryPolicy retryPolicy,
            EventMetrics metrics, EventProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.topicNames = topicNames;
        this.keyResolver = keyResolver;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        this.properties = properties;
    }

    public void publish(ConsumerRecord<String, String> original, EventEnvelope event, Throwable error) {
        int attempt = headerInt(original, ATTEMPT_HEADER, 0);
        EventRetryPolicy.Stage stage = stage(original.topic());
        EventRetryPolicy.Decision decision = retryPolicy.next(stage, attempt);
        String baseTopic = topicNames.baseTopic(original.topic());
        String target = target(baseTopic, decision.nextStage());
        RecordHeaders headers = copyHeaders(original);
        headers.remove(ATTEMPT_HEADER);
        headers.remove(NOT_BEFORE_HEADER);
        headers.add(ATTEMPT_HEADER, Integer.toString(attempt + 1).getBytes(StandardCharsets.UTF_8));
        headers.add(ERROR_HEADER, errorMessage(error).getBytes(StandardCharsets.UTF_8));
        if (!decision.delay().isZero()) {
            long notBefore = System.currentTimeMillis() + decision.delay().toMillis();
            headers.add(NOT_BEFORE_HEADER, Long.toString(notBefore).getBytes(StandardCharsets.UTF_8));
        }
        ProducerRecord<String, String> retry = new ProducerRecord<>(target, null, retryKey(original, event),
                codec.write(event), headers);
        try {
            kafkaTemplate.send(retry).get(properties.getKafka().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (decision.nextStage() == EventRetryPolicy.Stage.DEAD_LETTER) {
                metrics.deadLettered();
            } else {
                metrics.retried(decision.nextStage().name().toLowerCase());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to publish event retry for " + event.eventId(), exception);
        }
    }

    private EventRetryPolicy.Stage stage(String topic) {
        String base = topicNames.baseTopic(topic);
        if (topic.equals(base)) {
            return EventRetryPolicy.Stage.ORIGINAL;
        }
        if (topic.equals(topicNames.immediateRetry(base))) {
            return EventRetryPolicy.Stage.IMMEDIATE;
        }
        return EventRetryPolicy.Stage.DELAYED;
    }

    private String target(String baseTopic, EventRetryPolicy.Stage stage) {
        return switch (stage) {
            case IMMEDIATE -> topicNames.immediateRetry(baseTopic);
            case DELAYED -> topicNames.delayedRetry(baseTopic);
            case DEAD_LETTER -> topicNames.deadLetter(baseTopic);
            case ORIGINAL -> baseTopic;
        };
    }

    private RecordHeaders copyHeaders(ConsumerRecord<String, String> original) {
        RecordHeaders headers = new RecordHeaders();
        for (Header header : original.headers()) {
            headers.add(header.key(), header.value());
        }
        return headers;
    }

    private String retryKey(ConsumerRecord<String, String> original, EventEnvelope event) {
        try {
            return keyResolver.key(event);
        } catch (IllegalArgumentException exception) {
            return original.key() == null ? event.eventId().toString() : original.key();
        }
    }

    private int headerInt(ConsumerRecord<String, String> record, String name, int fallback) {
        Header header = record.headers().lastHeader(name);
        return header == null ? fallback : Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8));
    }

    private String errorMessage(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(message.length(), 1000));
    }
}
