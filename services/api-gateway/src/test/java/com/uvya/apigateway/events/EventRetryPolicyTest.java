package com.uvya.apigateway.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.uvya.apigateway.events.config.EventProperties;

class EventRetryPolicyTest {
    @Test
    void retriesMoveFromImmediateToDelayedToDeadLetter() {
        EventProperties properties = new EventProperties();
        properties.getRetry().setImmediateAttempts(1);
        properties.getRetry().setDelayedAttempts(1);
        EventRetryPolicy policy = new EventRetryPolicy(properties);

        assertThat(policy.next(EventRetryPolicy.Stage.ORIGINAL, 0).nextStage())
                .isEqualTo(EventRetryPolicy.Stage.IMMEDIATE);
        assertThat(policy.next(EventRetryPolicy.Stage.IMMEDIATE, 1).nextStage())
                .isEqualTo(EventRetryPolicy.Stage.DELAYED);
        assertThat(policy.next(EventRetryPolicy.Stage.DELAYED, 2).nextStage())
                .isEqualTo(EventRetryPolicy.Stage.DEAD_LETTER);
    }

    @Test
    void delayedBackoffIsExponentialAndJitteredWithinConfiguredBounds() {
        EventProperties properties = new EventProperties();
        properties.getRetry().setDelayedBaseDelayMs(1000);
        properties.getRetry().setDelayedMaxDelayMs(10000);
        properties.getRetry().setJitterRatio(0.2);
        EventRetryPolicy policy = new EventRetryPolicy(properties);

        Duration delay = policy.delayedDelay(2);

        assertThat(delay.toMillis()).isBetween(3200L, 4800L);
    }

    @Test
    void zeroJitterProducesDeterministicExponentialBackoff() {
        EventProperties properties = new EventProperties();
        properties.getRetry().setDelayedBaseDelayMs(1000);
        properties.getRetry().setJitterRatio(0);

        assertThat(new EventRetryPolicy(properties).delayedDelay(2)).isEqualTo(Duration.ofSeconds(4));
    }
}
