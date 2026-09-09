package com.uvya.apigateway.web;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class HealthController {

    private final ApplicationAvailability availability;

    public HealthController(ApplicationAvailability availability) {
        this.availability = availability;
    }

    @GetMapping("/health/live")
    public ResponseEntity<ProbeResponse> liveness() {
        boolean isUp = availability.getLivenessState() == LivenessState.CORRECT;
        return response(isUp, "liveness");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<ProbeResponse> readiness() {
        boolean isUp = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        return response(isUp, "readiness");
    }

    private ResponseEntity<ProbeResponse> response(boolean isUp, String probe) {
        HttpStatus status = isUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .body(new ProbeResponse(isUp ? "UP" : "DOWN", probe, Instant.now()));
    }

    public record ProbeResponse(String status, String probe, Instant timestamp) {
    }
}
