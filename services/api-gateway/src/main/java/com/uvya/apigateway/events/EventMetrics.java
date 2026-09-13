package com.uvya.apigateway.events;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

import com.uvya.apigateway.data.repository.OutboxEventRepository;

@Component
public class EventMetrics {
    private final MeterRegistry registry;
    private final Counter published;
    private final Counter publishFailures;
    private final Counter consumed;
    private final Counter duplicates;
    private final Counter deadLetters;
    private final Counter malformed;
    private final Counter brokerHealthChanges;
    private final Timer processing;
    private final ConcurrentMap<String, AtomicLong> lagValues = new ConcurrentHashMap<>();

    public EventMetrics(MeterRegistry registry, OutboxEventRepository outboxRepository) {
        this.registry = registry;
        published = Counter.builder("kafka.events.published").description("Outbox events acknowledged by Kafka")
                .register(registry);
        publishFailures = Counter.builder("kafka.events.publish.failures").register(registry);
        consumed = Counter.builder("kafka.events.consumed").register(registry);
        duplicates = Counter.builder("kafka.events.duplicates").register(registry);
        deadLetters = Counter.builder("kafka.events.dead.lettered").register(registry);
        malformed = Counter.builder("kafka.events.malformed").register(registry);
        brokerHealthChanges = Counter.builder("kafka.broker.health.changes").register(registry);
        processing = Timer.builder("kafka.events.processing").register(registry);
        Gauge.builder("outbox.events.pending", outboxRepository,
                repository -> repository.countByPublishedAtIsNull()).register(registry);
        Gauge.builder("kafka.broker.up", this, metrics -> metrics.brokerUp ? 1 : 0).register(registry);
    }

    private volatile boolean brokerUp;

    public void published() { published.increment(); }
    public void publishFailed() { publishFailures.increment(); }
    public void consumed() { consumed.increment(); }
    public void duplicate() { duplicates.increment(); }
    public void retried(String stage) {
        Counter.builder("kafka.events.retries").tag("stage", stage).register(registry).increment();
    }
    public void deadLettered() { deadLetters.increment(); }
    public void malformed() { malformed.increment(); }

    public void brokerHealth(boolean up) {
        if (brokerUp != up) {
            brokerHealthChanges.increment();
        }
        brokerUp = up;
    }

    public void lag(String group, String topic, int partition, long value) {
        String key = group + ":" + topic + ":" + partition;
        AtomicLong lag = lagValues.computeIfAbsent(key, ignored -> {
            AtomicLong created = new AtomicLong();
            Gauge.builder("kafka.consumer.lag", created, AtomicLong::get)
                    .tag("consumer_group", group)
                    .tag("topic", topic)
                    .tag("partition", Integer.toString(partition))
                    .register(this.registry);
            return created;
        });
        lag.set(Math.max(0, value));
    }

    public Timer.Sample startProcessing() {
        return Timer.start(registry);
    }

    public void stopProcessing(Timer.Sample sample) { sample.stop(processing); }
}
