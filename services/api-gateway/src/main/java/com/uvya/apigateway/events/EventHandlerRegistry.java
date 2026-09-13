package com.uvya.apigateway.events;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class EventHandlerRegistry {
    private final List<EventHandlerRegistration> registrations;

    public EventHandlerRegistry(List<EventHandlerRegistration> registrations) {
        this.registrations = registrations;
    }

    public EventConsumerHandler handlerFor(String eventType) {
        return registrations.stream().filter(registration -> registration.supports(eventType)).findFirst()
                .map(registration -> (EventConsumerHandler) registration::handle)
                .orElse(event -> { });
    }

    public static class UnknownEventHandlerException extends RuntimeException {
        public UnknownEventHandlerException(String eventType) {
            super("No consumer registered for event type " + eventType);
        }
    }
}
