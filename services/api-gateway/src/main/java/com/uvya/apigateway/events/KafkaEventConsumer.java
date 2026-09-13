package com.uvya.apigateway.events;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "uvya.events.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaEventConsumer {
    private final EventConsumerFramework framework;

    public KafkaEventConsumer(EventConsumerFramework framework) {
        this.framework = framework;
    }

    @KafkaListener(id = "uvya-event-consumer", topics = "#{@eventTopicNames.consumedTopics()}",
            groupId = "${uvya.events.consumer.group-id:uvya-events}",
            concurrency = "${uvya.events.consumer.concurrency:3}",
            containerFactory = "eventKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Consumer<?, ?> consumer) {
        framework.consume(record, consumer);
    }
}
