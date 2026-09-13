package com.uvya.apigateway.events;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("kafka")
@ConditionalOnProperty(name = {"uvya.events.kafka.enabled", "uvya.events.kafka.health.enabled"},
        havingValue = "true", matchIfMissing = true)
public class KafkaHealthIndicator implements HealthIndicator {
    private final String bootstrapServers;
    private final Duration timeout;
    private final EventMetrics metrics;

    public KafkaHealthIndicator(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${uvya.events.kafka.send-timeout:PT10S}") Duration timeout, EventMetrics metrics) {
        this.bootstrapServers = bootstrapServers;
        this.timeout = timeout;
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.toIntExact(timeout.toMillis()));
        try (AdminClient adminClient = AdminClient.create(config)) {
            int brokerCount = adminClient.describeCluster().nodes().get(timeout.toMillis(), TimeUnit.MILLISECONDS).size();
            metrics.brokerHealth(true);
            return Health.up().withDetail("brokers", brokerCount).build();
        } catch (Exception exception) {
            metrics.brokerHealth(false);
            return Health.down(exception).withDetail("bootstrapServers", bootstrapServers).build();
        }
    }
}
