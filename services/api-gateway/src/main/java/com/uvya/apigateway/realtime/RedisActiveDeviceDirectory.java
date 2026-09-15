package com.uvya.apigateway.realtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.uvya.apigateway.events.config.FanoutProperties;

@Component
public class RedisActiveDeviceDirectory implements ActiveDeviceDirectory {
    private final StringRedisTemplate redis;
    private final FanoutProperties properties;

    public RedisActiveDeviceDirectory(StringRedisTemplate redis, FanoutProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public List<ActiveDevice> activeDevices(UUID userId) {
        Set<String> deviceIds = redis.opsForSet().members(userDevicesKey(userId));
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }
        List<String> sortedDeviceIds = new ArrayList<>(deviceIds);
        Collections.sort(sortedDeviceIds);
        List<ActiveDevice> activeDevices = new ArrayList<>();
        for (String value : sortedDeviceIds) {
            try {
                UUID deviceId = UUID.fromString(value);
                Map<Object, Object> metadata = redis.opsForHash().entries(deviceKey(deviceId));
                String connectionId = stringValue(metadata.get("connection_id"));
                if (connectionId != null && !connectionId.isBlank()) {
                    activeDevices.add(new ActiveDevice(userId, deviceId, connectionId,
                            stringValue(metadata.get("gateway_id"))));
                }
            } catch (IllegalArgumentException ignored) {
                // A stale or malformed Redis set member is not an active device.
            }
        }
        return activeDevices;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String userDevicesKey(UUID userId) {
        return properties.getRedisKeyPrefix() + ":user:" + userId + ":devices";
    }

    private String deviceKey(UUID deviceId) {
        return properties.getRedisKeyPrefix() + ":device:" + deviceId;
    }
}
