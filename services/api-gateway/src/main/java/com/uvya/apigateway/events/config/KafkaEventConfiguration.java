package com.uvya.apigateway.events.config;

import java.util.List;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.uvya.apigateway.events.EventTopicNames;

@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "uvya.events.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaEventConfiguration {
    @Bean
    public KafkaAdmin.NewTopics eventTopics(EventTopicNames topicNames, EventProperties properties) {
        List<NewTopic> topics = topicNames.allProvisionedTopics().stream()
                .map(name -> new NewTopic(name, properties.getTopics().getPartitions(),
                        properties.getTopics().getReplicationFactor()))
                .toList();
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> eventKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, EventProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(properties.getConsumer().getConcurrency());
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> eventKafkaTemplate(
            org.springframework.kafka.core.ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
