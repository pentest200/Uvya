package com.uvya.apigateway.realtime;

import java.util.List;
import java.util.UUID;

public interface ActiveDeviceDirectory {
    List<ActiveDevice> activeDevices(UUID userId);
}
