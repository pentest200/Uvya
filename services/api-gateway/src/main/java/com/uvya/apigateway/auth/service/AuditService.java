package com.uvya.apigateway.auth.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.uvya.apigateway.auth.domain.AuthAuditLogEntity;
import com.uvya.apigateway.auth.repository.AuthAuditLogRepository;

@Service
public class AuditService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);
    private final AuthAuditLogRepository repository;

    public AuditService(AuthAuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String eventType, UUID userId, UUID deviceId, UUID sessionId, RequestContext context) {
        repository.save(new AuthAuditLogEntity(eventType, userId, deviceId, sessionId,
                context.ipAddress(), context.requestId(), "{}", Instant.now()));
        LOGGER.info("authentication_event eventType={} userId={} deviceId={} sessionId={}",
                eventType, userId, deviceId, sessionId);
    }
}
