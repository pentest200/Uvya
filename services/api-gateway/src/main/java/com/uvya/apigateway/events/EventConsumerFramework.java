package com.uvya.apigateway.events;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.uvya.apigateway.events.config.EventProperties;

@Component
@ConditionalOnProperty(prefix = "uvya.events.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventConsumerFramework {
    private final EventEnvelopeCodec codec;
    private final IdempotentEventConsumer idempotentConsumer;
    private final EventHandlerRegistry handlerRegistry;
    private final KafkaRetryPublisher retryPublisher;
    private final EventMetrics metrics;
    private final EventProperties properties;
    private final MeterRegistry meterRegistry;

    public EventConsumerFramework(EventEnvelopeCodec codec, IdempotentEventConsumer idempotentConsumer,
            EventHandlerRegistry handlerRegistry, KafkaRetryPublisher retryPublisher, EventMetrics metrics,
            EventProperties properties, MeterRegistry meterRegistry) {
        this.codec = codec;
        this.idempotentConsumer = idempotentConsumer;
        this.handlerRegistry = handlerRegistry;
        this.retryPublisher = retryPublisher;
        this.metrics = metrics;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public void consume(ConsumerRecord<String, String> record, Consumer<?, ?> consumer) {
        EventEnvelope event;
        try {
            event = codec.read(record.value());
        } catch (RuntimeException exception) {
            metrics.malformed();
            throw exception;
        }
        recordLag(record, consumer);
        waitForDelayedRetry(record);
        Timer.Sample sample = metrics.startProcessing();
        String previousTraceId = MDC.get("traceId");
        String previousCorrelationId = MDC.get("correlationId");
        MDC.put("traceId", event.traceId());
        MDC.put("correlationId", event.correlationId());
        try {
            idempotentConsumer.consume(properties.getConsumer().getGroupId(), event, record.topic(),
                    record.partition(), record.offset(), handlerRegistry.handlerFor(event.eventType()));
        } catch (RuntimeException exception) {
            retryPublisher.publish(record, event, exception);
        } finally {
            metrics.stopProcessing(sample);
            restoreMdc("traceId", previousTraceId);
            restoreMdc("correlationId", previousCorrelationId);
        }
    }

    public void consume(ConsumerRecord<String, String> record) {
        consume(record, null);
    }

    private void waitForDelayedRetry(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaRetryPublisher.NOT_BEFORE_HEADER);
        if (header == null) {
            return;
        }
        long notBefore = Long.parseLong(new String(header.value(), StandardCharsets.UTF_8));
        try {
            while ((notBefore - System.currentTimeMillis()) > 0) {
                Thread.sleep(Math.min(notBefore - System.currentTimeMillis(),
                        properties.getRetry().getDelayedMaxDelayMs()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Delayed retry was interrupted", exception);
        }
    }

    private void recordLag(ConsumerRecord<String, String> record, Consumer<?, ?> consumer) {
        if (consumer == null) {
            return;
        }
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        try {
            long highWatermark = consumer.endOffsets(Set.of(partition)).getOrDefault(partition, record.offset() + 1);
            metrics.lag(properties.getConsumer().getGroupId(), record.topic(), record.partition(),
                    highWatermark - record.offset() - 1);
        } catch (RuntimeException ignored) {
            // Lag is diagnostic; it must not prevent a durable event from being handled.
        }
    }

    private void restoreMdc(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
