package com.uvya.apigateway.events;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.uvya.apigateway.data.domain.OutboxEventEntity;
import com.uvya.apigateway.data.event.OutboxPublisher;
import com.uvya.apigateway.events.config.EventProperties;

@Component
@ConditionalOnProperty(prefix = "uvya.events.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaEventProducer implements OutboxPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventEnvelopeCodec codec;
    private final EventTopicNames topicNames;
    private final EventPartitionKeyResolver keyResolver;
    private final EventMetrics metrics;
    private final EventProperties properties;

    public KafkaEventProducer(@Qualifier("eventKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            EventEnvelopeCodec codec,
            EventTopicNames topicNames, EventPartitionKeyResolver keyResolver, EventMetrics metrics,
            EventProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.topicNames = topicNames;
        this.keyResolver = keyResolver;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxEventEntity event) {
        EventEnvelope envelope = EventEnvelope.from(event);
        String topic = topicNames.topicFor(envelope.eventType());
        String key = keyResolver.key(envelope);
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, key, codec.write(envelope),
                new RecordHeaders());
        addHeader(record, "event-id", envelope.eventId().toString());
        addHeader(record, "event-type", envelope.eventType());
        addHeader(record, "trace-id", envelope.traceId());
        addHeader(record, "correlation-id", envelope.correlationId());
        addHeader(record, "idempotency-key", envelope.idempotencyKey());
        try {
            SendResult<String, String> result = kafkaTemplate.send(record)
                    .get(properties.getKafka().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (result == null) {
                throw new IllegalStateException("Kafka returned no send result");
            }
            metrics.published();
        } catch (Exception exception) {
            metrics.publishFailed();
            throw new IllegalStateException("Kafka publication failed for " + envelope.eventId(), exception);
        }
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
