package com.uvya.apigateway.fanout;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class KafkaAndFanoutEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return enabled(context, "uvya.events.kafka.enabled") && enabled(context, "uvya.fanout.enabled");
    }

    private boolean enabled(ConditionContext context, String property) {
        return Boolean.parseBoolean(context.getEnvironment().getProperty(property, "true"));
    }
}
