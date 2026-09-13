package com.uvya.apigateway.events;

@FunctionalInterface
public interface EventConsumerHandler {
    void handle(EventEnvelope event) throws Exception;
}
