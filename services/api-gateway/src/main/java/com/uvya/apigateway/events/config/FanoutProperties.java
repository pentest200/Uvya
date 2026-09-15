package com.uvya.apigateway.events.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "uvya.fanout")
public class FanoutProperties {
    private boolean enabled = true;
    private int smallGroupMemberLimit = 100;
    private String consumerGroup = "uvya-fanout";
    private int consumerConcurrency = 3;
    private int maxDeliveryAttempts = 5;
    private Duration retryAfter = Duration.ofSeconds(30);
    private Duration retryPollInterval = Duration.ofSeconds(5);
    private long retryPollIntervalMs = 5000;
    private String redisKeyPrefix = "uvya:ws";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSmallGroupMemberLimit() { return smallGroupMemberLimit; }
    public void setSmallGroupMemberLimit(int smallGroupMemberLimit) { this.smallGroupMemberLimit = smallGroupMemberLimit; }
    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
    public int getConsumerConcurrency() { return consumerConcurrency; }
    public void setConsumerConcurrency(int consumerConcurrency) { this.consumerConcurrency = consumerConcurrency; }
    public int getMaxDeliveryAttempts() { return maxDeliveryAttempts; }
    public void setMaxDeliveryAttempts(int maxDeliveryAttempts) { this.maxDeliveryAttempts = maxDeliveryAttempts; }
    public Duration getRetryAfter() { return retryAfter; }
    public void setRetryAfter(Duration retryAfter) { this.retryAfter = retryAfter; }
    public Duration getRetryPollInterval() { return retryPollInterval; }
    public void setRetryPollInterval(Duration retryPollInterval) { this.retryPollInterval = retryPollInterval; }
    public long getRetryPollIntervalMs() { return retryPollIntervalMs; }
    public void setRetryPollIntervalMs(long retryPollIntervalMs) { this.retryPollIntervalMs = retryPollIntervalMs; }
    public String getRedisKeyPrefix() { return redisKeyPrefix; }
    public void setRedisKeyPrefix(String redisKeyPrefix) { this.redisKeyPrefix = redisKeyPrefix; }
}
