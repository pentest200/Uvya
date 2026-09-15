package com.uvya.apigateway.realtime;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface RealtimeRoutePublisher {
    void publishToDevice(ActiveDevice device, ObjectNode event);
}
