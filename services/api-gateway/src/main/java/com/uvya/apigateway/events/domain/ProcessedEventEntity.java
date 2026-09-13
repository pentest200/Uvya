package com.uvya.apigateway.events.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "processed_events", uniqueConstraints = {
        @UniqueConstraint(name = "uq_processed_events_group_event", columnNames = {"consumer_group", "event_id"})
})
public class ProcessedEventEntity {
    @Id
    private UUID id;
    @Column(name = "consumer_group", nullable = false, length = 255)
    private String consumerGroup;
    @Column(name = "event_id", nullable = false)
    private UUID eventId;
    @Column(nullable = false, length = 255)
    private String topic;
    @Column(name = "partition_number", nullable = false)
    private int partitionNumber;
    @Column(name = "offset_number", nullable = false)
    private long offsetNumber;
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() { }

    public ProcessedEventEntity(UUID id, String consumerGroup, UUID eventId, String topic, int partitionNumber,
            long offsetNumber, Instant processedAt) {
        this.id = id;
        this.consumerGroup = consumerGroup;
        this.eventId = eventId;
        this.topic = topic;
        this.partitionNumber = partitionNumber;
        this.offsetNumber = offsetNumber;
        this.processedAt = processedAt;
    }

    public UUID getId() { return id; }
    public String getConsumerGroup() { return consumerGroup; }
    public UUID getEventId() { return eventId; }
    public String getTopic() { return topic; }
    public int getPartitionNumber() { return partitionNumber; }
    public long getOffsetNumber() { return offsetNumber; }
    public Instant getProcessedAt() { return processedAt; }
}
