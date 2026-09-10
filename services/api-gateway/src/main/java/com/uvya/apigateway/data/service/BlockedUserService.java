package com.uvya.apigateway.data.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uvya.apigateway.data.domain.BlockedUserEntity;
import com.uvya.apigateway.data.domain.BlockedUserId;
import com.uvya.apigateway.data.repository.BlockedUserRepository;

@Service
public class BlockedUserService {
    private final BlockedUserRepository repository;

    public BlockedUserService(BlockedUserRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void block(UUID blockerUserId, UUID blockedUserId) {
        if (blockerUserId == null || blockedUserId == null || blockerUserId.equals(blockedUserId)) {
            throw new DataFoundationException("A user cannot block itself");
        }
        if (!repository.existsBlock(blockerUserId, blockedUserId)) {
            repository.save(new BlockedUserEntity(blockerUserId, blockedUserId, Instant.now()));
        }
    }

    @Transactional
    public void unblock(UUID blockerUserId, UUID blockedUserId) {
        repository.deleteById(new BlockedUserId(blockerUserId, blockedUserId));
    }

    @Transactional(readOnly = true)
    public boolean isBlocked(UUID blockerUserId, UUID blockedUserId) {
        return repository.existsBlock(blockerUserId, blockedUserId);
    }
}
