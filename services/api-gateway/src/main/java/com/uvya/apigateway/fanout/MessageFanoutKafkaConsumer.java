package com.uvya.apigateway.fanout;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Conditional;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.uvya.apigateway.events.EventConsumerFramework;
import com.uvya.apigateway.events.EventEnvelope;
import com.uvya.apigateway.events.config.FanoutProperties;

@Component
@Conditional(KafkaAndFanoutEnabledCondition.class)
public class MessageFanoutKafkaConsumer {
    private final EventConsumerFramework framework;
    private final MessageFanoutService fanoutService;
    private final FanoutProperties properties;

    public MessageFanoutKafkaConsumer(EventConsumerFramework framework, MessageFanoutService fanoutService,
            FanoutProperties properties) {
        this.framework = framework;
        this.fanoutService = fanoutService;
        this.properties = properties;
    }

    @KafkaListener(id = "uvya-message-fanout", topics = "#{@eventTopicNames.messageFanoutTopics()}",
            groupId = "${uvya.fanout.consumer-group:uvya-fanout}",
            concurrency = "${uvya.fanout.consumer-concurrency:3}",
            containerFactory = "eventKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Consumer<?, ?> consumer) {
        framework.consume(record, consumer, properties.getConsumerGroup(), this::handle);
    }

    private void handle(EventEnvelope event) {
        if ("message.created".equals(event.eventType())) {
            fanoutService.handleCreated(event);
        } else if ("message.delivered".equals(event.eventType())) {
            fanoutService.handleDelivered(event);
        } else if (event.eventType().startsWith("message.reaction.")
                || "message.pinned".equals(event.eventType())
                || "message.unpinned".equals(event.eventType())) {
            fanoutService.handleInteraction(event);
        }
    }
}
