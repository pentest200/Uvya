package com.uvya.apigateway.auth.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.auth.domain.AuthAuditLogEntity;

public interface AuthAuditLogRepository extends JpaRepository<AuthAuditLogEntity, UUID> {
}
