package com.uvya.apigateway.data.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uvya.apigateway.data.domain.OutboxEventEntity;
import com.uvya.apigateway.data.event.OutboxPublisher;
import com.uvya.apigateway.data.repository.OutboxEventRepository;

@Service
public class OutboxPublicationService {
    private final OutboxEventRepository repository;

    public OutboxPublicationService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void publish(UUID eventId, OutboxPublisher publisher) {
        OutboxEventEntity event = repository.findById(eventId)
                .orElseThrow(() -> new DataFoundationException("Outbox event not found"));
        if (event.getPublishedAt() != null) {
            return;
        }
        event.markPublishAttempt(Instant.now());
        publisher.publish(event);
        event.markPublished(Instant.now());
        repository.save(event);
    }

    @Transactional
    public int publishAvailable(OutboxPublisher publisher) {
        int published = 0;
        for (OutboxEventEntity event : repository
                .findTop100ByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByAvailableAtAscOccurredAtAsc(
                        Instant.now())) {
            publish(event.getEventId(), publisher);
            published++;
        }
        return published;
    }
}
