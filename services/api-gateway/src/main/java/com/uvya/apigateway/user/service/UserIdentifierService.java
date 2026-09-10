package com.uvya.apigateway.user.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uvya.apigateway.auth.repository.UserRepository;
import com.uvya.apigateway.user.domain.ContactIdentifierType;
import com.uvya.apigateway.user.domain.UserIdentifierEntity;
import com.uvya.apigateway.user.repository.UserIdentifierRepository;

@Service
public class UserIdentifierService {
    private final UserIdentifierRepository repository;
    private final UserRepository userRepository;
    private final ContactNormalizer normalizer;

    public UserIdentifierService(UserIdentifierRepository repository, UserRepository userRepository,
            ContactNormalizer normalizer) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.normalizer = normalizer;
    }

    @Transactional
    public void indexEmail(UUID userId, String email) {
        String normalized = normalizer.normalize(ContactIdentifierType.EMAIL, email);
        String hash = normalizer.hash(normalized);
        if (repository.findByUserIdAndIdentifierType(userId, ContactIdentifierType.EMAIL).isEmpty()
                && userRepository.existsById(userId)) {
            repository.save(new UserIdentifierEntity(userId, ContactIdentifierType.EMAIL, hash, true, Instant.now()));
        }
    }
}
