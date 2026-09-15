package com.uvya.apigateway.fanout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.uvya.apigateway.events.config.FanoutProperties;

@Component
@ConditionalOnProperty(prefix = "uvya.fanout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryRetryJob {
    private final MessageFanoutService fanoutService;
    private final FanoutProperties properties;

    public DeliveryRetryJob(MessageFanoutService fanoutService, FanoutProperties properties) {
        this.fanoutService = fanoutService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${uvya.fanout.retry-poll-interval-ms:5000}")
    public void retryPendingDeliveries() {
        if (properties.isEnabled()) {
            fanoutService.retryPending();
        }
    }
}
