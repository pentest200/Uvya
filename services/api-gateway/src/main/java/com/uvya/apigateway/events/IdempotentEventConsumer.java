package com.uvya.apigateway.events;

import org.springframework.stereotype.Component;

@Component
public class IdempotentEventConsumer {
    private final ProcessedEventService processedEventService;
    private final EventMetrics metrics;

    public IdempotentEventConsumer(ProcessedEventService processedEventService, EventMetrics metrics) {
        this.processedEventService = processedEventService;
        this.metrics = metrics;
    }

    public boolean consume(String consumerGroup, EventEnvelope event, String topic, int partition, long offset,
            EventConsumerHandler handler) {
        boolean processed = processedEventService.process(consumerGroup, event, topic, partition, offset, handler);
        if (processed) {
            metrics.consumed();
        } else {
            metrics.duplicate();
        }
        return processed;
    }
}
