package com.uvya.apigateway.events;

public interface EventHandlerRegistration {
    boolean supports(String eventType);

    void handle(EventEnvelope event) throws Exception;
}
