package com.uvya.apigateway.events;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.uvya.apigateway.events.config.EventProperties;

@Component
public class EventRetryPolicy {
    public enum Stage {
        ORIGINAL,
        IMMEDIATE,
        DELAYED,
        DEAD_LETTER
    }

    public record Decision(Stage nextStage, Duration delay) { }

    private final EventProperties properties;

    public EventRetryPolicy(EventProperties properties) {
        this.properties = properties;
    }

    public Decision next(Stage stage, int attempt) {
        if (stage == Stage.ORIGINAL) {
            return new Decision(Stage.IMMEDIATE, Duration.ZERO);
        }
        if (stage == Stage.IMMEDIATE && attempt < properties.getRetry().getImmediateAttempts()) {
            return new Decision(Stage.IMMEDIATE, Duration.ZERO);
        }
        if (stage == Stage.IMMEDIATE || stage == Stage.ORIGINAL) {
            return new Decision(Stage.DELAYED, delayedDelay(attempt));
        }
        if (stage == Stage.DELAYED
                && attempt < properties.getRetry().getImmediateAttempts()
                        + properties.getRetry().getDelayedAttempts()) {
            return new Decision(Stage.DELAYED, delayedDelay(attempt));
        }
        return new Decision(Stage.DEAD_LETTER, Duration.ZERO);
    }

    public Duration delayedDelay(int attempt) {
        long base = properties.getRetry().getDelayedBaseDelayMs();
        long maximum = properties.getRetry().getDelayedMaxDelayMs();
        int exponent = Math.max(0, Math.min(attempt, 30));
        long exponential = base;
        for (int index = 0; index < exponent && exponential < maximum; index++) {
            exponential = exponential > maximum / 2 ? maximum : exponential * 2;
        }
        double jitter = Math.max(0, Math.min(1, properties.getRetry().getJitterRatio()));
        double multiplier = jitter == 0 ? 1.0
                : 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        return Duration.ofMillis(Math.max(0, Math.min(maximum, (long) (exponential * multiplier))));
    }
}
