package com.uvya.apigateway.events;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class EventEnvelopeCodec {
    private final ObjectMapper objectMapper;

    public EventEnvelopeCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(EventEnvelope event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize event " + event.eventId(), exception);
        }
    }

    public EventEnvelope read(String value) {
        try {
            return objectMapper.readValue(value, EventEnvelope.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid event envelope", exception);
        }
    }
}
