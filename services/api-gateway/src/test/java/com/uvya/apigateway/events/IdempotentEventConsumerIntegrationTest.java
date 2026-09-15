package com.uvya.apigateway.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uvya.apigateway.auth.service.AuthRateLimiter;
import com.uvya.apigateway.events.repository.ProcessedEventRepository;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class IdempotentEventConsumerIntegrationTest {
    @Autowired private IdempotentEventConsumer consumer;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AuthRateLimiter rateLimiter;

    @Test
    void deliveringTheSameEventMultipleTimesRunsTheHandlerOnce() {
        EventEnvelope event = event();
        AtomicInteger sideEffects = new AtomicInteger();
        EventConsumerHandler handler = ignored -> sideEffects.incrementAndGet();

        assertThat(consumer.consume("notification-workers", event, "notification.requested", 0, 12, handler))
                .isTrue();
        assertThat(consumer.consume("notification-workers", event, "notification.requested", 0, 13, handler))
                .isFalse();

        assertThat(sideEffects).hasValue(1);
        assertThat(processedEventRepository.countByConsumerGroup("notification-workers")).isEqualTo(1);
    }

    @Test
    void failedHandlingDoesNotPoisonTheIdempotencyRecordAndCanBeRetried() {
        EventEnvelope event = event();
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> consumer.consume("search-workers", event, "search.index.requested", 1, 4,
                ignored -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("temporary dependency failure");
                })).isInstanceOf(RuntimeException.class);
        assertThat(processedEventRepository.countByConsumerGroup("search-workers")).isZero();

        assertThat(consumer.consume("search-workers", event, "search.index.requested", 1, 5,
                ignored -> attempts.incrementAndGet())).isTrue();
        assertThat(attempts).hasValue(2);
        assertThat(processedEventRepository.countByConsumerGroup("search-workers")).isEqualTo(1);
    }

    @Test
    void fanoutKafkaRedeliveryRunsTheFanoutHandlerOncePerEventId() {
        EventEnvelope event = new EventEnvelope(UUID.randomUUID(), "message.created", 1, Instant.now(),
                "trace-id", "correlation-id", UUID.randomUUID().toString(), objectMapper.createObjectNode()
                        .put("messageId", UUID.randomUUID().toString()));
        AtomicInteger fanoutAttempts = new AtomicInteger();

        assertThat(consumer.consume("uvya-fanout", event, "message.created", 0, 20,
                ignored -> fanoutAttempts.incrementAndGet())).isTrue();
        assertThat(consumer.consume("uvya-fanout", event, "message.created", 0, 21,
                ignored -> fanoutAttempts.incrementAndGet())).isFalse();

        assertThat(fanoutAttempts).hasValue(1);
        assertThat(processedEventRepository.countByConsumerGroup("uvya-fanout")).isEqualTo(1);
    }

    private EventEnvelope event() {
        return new EventEnvelope(UUID.randomUUID(), "notification.requested", 1, Instant.now(), "trace-id",
                "correlation-id", UUID.randomUUID().toString(), objectMapper.createObjectNode()
                        .put("userId", UUID.randomUUID().toString()));
    }
}
