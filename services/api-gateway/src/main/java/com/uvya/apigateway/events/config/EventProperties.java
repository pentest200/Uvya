package com.uvya.apigateway.events.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "uvya.events")
public class EventProperties {
    private final Kafka kafka = new Kafka();
    private final Outbox outbox = new Outbox();
    private final Consumer consumer = new Consumer();
    private final Retry retry = new Retry();
    private final Topics topics = new Topics();

    public Kafka getKafka() { return kafka; }
    public Outbox getOutbox() { return outbox; }
    public Consumer getConsumer() { return consumer; }
    public Retry getRetry() { return retry; }
    public Topics getTopics() { return topics; }

    public static class Kafka {
        private boolean enabled = true;
        private final Health health = new Health();
        private Duration sendTimeout = Duration.ofSeconds(10);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Health getHealth() { return health; }
        public Duration getSendTimeout() { return sendTimeout; }
        public void setSendTimeout(Duration sendTimeout) { this.sendTimeout = sendTimeout; }
    }

    public static class Health {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Outbox {
        private boolean enabled = true;
        private long pollIntervalMs = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    }

    public static class Consumer {
        private String groupId = "uvya-events";
        private int concurrency = 3;

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    }

    public static class Retry {
        private int immediateAttempts = 1;
        private int delayedAttempts = 3;
        private long delayedBaseDelayMs = 1000;
        private long delayedMaxDelayMs = 60000;
        private double jitterRatio = 0.2;

        public int getImmediateAttempts() { return immediateAttempts; }
        public void setImmediateAttempts(int immediateAttempts) { this.immediateAttempts = immediateAttempts; }
        public int getDelayedAttempts() { return delayedAttempts; }
        public void setDelayedAttempts(int delayedAttempts) { this.delayedAttempts = delayedAttempts; }
        public long getDelayedBaseDelayMs() { return delayedBaseDelayMs; }
        public void setDelayedBaseDelayMs(long delayedBaseDelayMs) { this.delayedBaseDelayMs = delayedBaseDelayMs; }
        public long getDelayedMaxDelayMs() { return delayedMaxDelayMs; }
        public void setDelayedMaxDelayMs(long delayedMaxDelayMs) { this.delayedMaxDelayMs = delayedMaxDelayMs; }
        public double getJitterRatio() { return jitterRatio; }
        public void setJitterRatio(double jitterRatio) { this.jitterRatio = jitterRatio; }
    }

    public static class Topics {
        private int partitions = 6;
        private short replicationFactor = 1;

        public int getPartitions() { return partitions; }
        public void setPartitions(int partitions) { this.partitions = partitions; }
        public short getReplicationFactor() { return replicationFactor; }
        public void setReplicationFactor(short replicationFactor) { this.replicationFactor = replicationFactor; }
    }
}
