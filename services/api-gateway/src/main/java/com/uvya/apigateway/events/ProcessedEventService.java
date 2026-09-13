package com.uvya.apigateway.events;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uvya.apigateway.events.domain.ProcessedEventEntity;
import com.uvya.apigateway.events.repository.ProcessedEventRepository;

@Service
public class ProcessedEventService {
    private final ProcessedEventRepository repository;

    public ProcessedEventService(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean process(String consumerGroup, EventEnvelope event, String topic, int partition, long offset,
            EventConsumerHandler handler) {
        if (repository.existsByConsumerGroupAndEventId(consumerGroup, event.eventId())) {
            return false;
        }
        repository.saveAndFlush(new ProcessedEventEntity(UUID.randomUUID(), consumerGroup, event.eventId(), topic,
                partition, offset, Instant.now()));
        try {
            handler.handle(event);
            return true;
        } catch (Exception exception) {
            throw new EventProcessingException(event.eventId(), exception);
        }
    }

    public static class EventProcessingException extends RuntimeException {
        public EventProcessingException(UUID eventId, Exception cause) {
            super("Event handler failed for " + eventId, cause);
        }
    }
}
