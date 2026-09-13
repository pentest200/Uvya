package com.uvya.apigateway.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.uvya.apigateway.data.service.OutboxPublicationService;

@Component
@ConditionalOnProperty(name = {"uvya.events.kafka.enabled", "uvya.events.outbox.enabled"},
        havingValue = "true", matchIfMissing = true)
public class OutboxPublisherJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisherJob.class);

    private final OutboxPublicationService publicationService;
    private final KafkaEventProducer producer;

    public OutboxPublisherJob(OutboxPublicationService publicationService, KafkaEventProducer producer) {
        this.publicationService = publicationService;
        this.producer = producer;
    }

    @Scheduled(fixedDelayString = "${uvya.events.outbox.poll-interval-ms:1000}")
    public void publishAvailableEvents() {
        try {
            int published = publicationService.publishAvailable(producer);
            if (published > 0) {
                LOGGER.debug("outbox_events_published count={}", published);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("outbox_publication_cycle_failed", exception);
        }
    }
}
