package com.uvya.apigateway.realtime;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uvya.apigateway.events.config.FanoutProperties;

@Component
public class RedisRealtimeRoutePublisher implements RealtimeRoutePublisher {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FanoutProperties properties;

    public RedisRealtimeRoutePublisher(StringRedisTemplate redis, ObjectMapper objectMapper,
            FanoutProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publishToDevice(ActiveDevice device, ObjectNode event) {
        ObjectNode route = objectMapper.createObjectNode();
        route.put("targetDeviceId", device.deviceId().toString());
        route.put("originGateway", "api-gateway");
        route.set("event", event);
        try {
            Long subscribers = redis.convertAndSend(routeChannel(), objectMapper.writeValueAsString(route));
            if (subscribers == null || subscribers < 1) {
                throw new IllegalStateException("No WebSocket gateway subscribed to the route channel");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize realtime route", exception);
        }
    }

    private String routeChannel() { return properties.getRedisKeyPrefix() + ":routes"; }
}
