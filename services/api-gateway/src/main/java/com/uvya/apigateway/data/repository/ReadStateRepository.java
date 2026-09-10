package com.uvya.apigateway.data.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uvya.apigateway.data.domain.ReadStateEntity;
import com.uvya.apigateway.data.domain.ReadStateId;

public interface ReadStateRepository extends JpaRepository<ReadStateEntity, ReadStateId> {
}
