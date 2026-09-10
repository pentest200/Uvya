package com.uvya.apigateway.user.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.uvya.apigateway.auth.domain.UserEntity;
import com.uvya.apigateway.auth.domain.UserStatus;
import com.uvya.apigateway.auth.repository.UserRepository;
import com.uvya.apigateway.auth.service.AuditService;
import com.uvya.apigateway.auth.service.RequestContext;
import com.uvya.apigateway.data.service.BlockedUserService;
import com.uvya.apigateway.user.cache.UserProfileCache;
import com.uvya.apigateway.user.domain.ContactIdentifierType;
import com.uvya.apigateway.user.domain.Discoverability;
import com.uvya.apigateway.user.domain.UserIdentifierEntity;
import com.uvya.apigateway.user.domain.UserProfileEntity;
import com.uvya.apigateway.user.repository.ContactIdentifierRepository;
import com.uvya.apigateway.user.repository.UserIdentifierRepository;
import com.uvya.apigateway.user.repository.UserProfileRepository;
import com.uvya.apigateway.user.web.ContactIdentifierRequest;
import com.uvya.apigateway.user.web.ContactMatchResponse;
import com.uvya.apigateway.user.web.ContactUploadRequest;
import com.uvya.apigateway.user.web.ContactUploadResponse;
import com.uvya.apigateway.user.web.UpdateUserProfileRequest;
import com.uvya.apigateway.user.web.UserProfileResponse;

@Service
public class UserService {
    private static final int MAX_SEARCH_SIZE = 50;
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final UserIdentifierRepository identifierRepository;
    private final ContactIdentifierRepository contactRepository;
    private final BlockedUserService blockedUserService;
    private final UserProfileCache profileCache;
    private final UserIdentifierService identifierService;
    private final ContactNormalizer normalizer;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public UserService(UserRepository userRepository, UserProfileRepository profileRepository,
            UserIdentifierRepository identifierRepository, ContactIdentifierRepository contactRepository,
            BlockedUserService blockedUserService, UserProfileCache profileCache,
            UserIdentifierService identifierService, ContactNormalizer normalizer, AuditService auditService,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.identifierRepository = identifierRepository;
        this.contactRepository = contactRepository;
        this.blockedUserService = blockedUserService;
        this.profileCache = profileCache;
        this.identifierService = identifierService;
        this.normalizer = normalizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UserProfileResponse getMe(UUID userId) {
        UserEntity user = user(userId);
        identifierService.indexEmail(userId, user.getEmail());
        return privateResponse(user, profile(userId));
    }

    @Transactional
    public UserProfileResponse updateMe(UUID userId, UpdateUserProfileRequest request, RequestContext context) {
        UserEntity user = user(userId);
        UserProfileEntity profile = profile(userId);
        String username = request.username() == null ? null : request.username().trim();
        String normalizedUsername = username == null ? null : username.toLowerCase(java.util.Locale.ROOT);
        if (normalizedUsername != null && profileRepository.findByUsernameNormalized(normalizedUsername)
                .filter(existing -> !existing.getUserId().equals(userId)).isPresent()) {
            throw new UsernameTakenException();
        }
        validateJson(request.avatarMetadata());
        validateJson(request.privacySettings());
        profile.update(username, normalizedUsername, request.displayName(), request.bio(), copy(request.avatarMetadata()),
                copy(request.privacySettings()), request.discoverability(), Instant.now());
        try {
            profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException exception) {
            throw new UsernameTakenException();
        }
        profileCache.evict(userId);
        auditService.record("PROFILE_UPDATED", userId, null, null, context);
        return privateResponse(user, profile);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getPublic(UUID viewerId, UUID targetId) {
        UserEntity target = user(targetId);
        if (viewerId.equals(targetId)) {
            return privateResponse(target, profile(targetId));
        }
        if (target.getStatus() != UserStatus.ACTIVE || blocked(viewerId, targetId)) {
            throw new UserNotFoundException();
        }
        UserProfileEntity profile = profileRepository.findByUserId(targetId).orElse(null);
        if (profile == null) {
            throw new UserNotFoundException();
        }
        if (profile.getDiscoverability() == Discoverability.NOBODY
                || (profile.getDiscoverability() == Discoverability.CONTACTS_ONLY
                    && (!contactMatchingAllowed(profile) || !hasContactFor(viewerId, target)))) {
            throw new UserNotFoundException();
        }
        UserProfileResponse cached = profileCache.get(targetId).orElse(null);
        if (cached != null && cached.discoverability() == Discoverability.PUBLIC) {
            return cached;
        }
        UserProfileResponse response = publicResponse(target, profile);
        if (profile.getDiscoverability() == Discoverability.PUBLIC) {
            profileCache.put(response);
        }
        return response;
    }

    @Transactional(readOnly = true)
    public Page<UserProfileResponse> search(UUID viewerId, String query, int page, int size) {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.length() > 100 || page < 0 || size < 1 || size > MAX_SEARCH_SIZE) {
            throw new UserServiceException("Invalid search pagination");
        }
        Pageable pageable = PageRequest.of(page, size);
        Page<UserProfileEntity> profiles = profileRepository.searchPublic(safeQuery, UserStatus.ACTIVE,
                Discoverability.PUBLIC, pageable);
        List<UserProfileResponse> visible = profiles.stream()
                .filter(profile -> !blocked(viewerId, profile.getUserId()))
                .map(profile -> publicResponse(user(profile.getUserId()), profile))
                .toList();
        return new PageImpl<>(visible, pageable, profiles.getTotalElements());
    }

    @Transactional
    public ContactUploadResponse uploadContacts(UUID ownerId, ContactUploadRequest request, RequestContext context) {
        UserEntity owner = user(ownerId);
        Map<String, ContactIdentifierRequest> uniqueContacts = new LinkedHashMap<>();
        for (ContactIdentifierRequest contact : request.contacts()) {
            String normalized = normalizer.normalize(contact.identifierType(), contact.identifier());
            String hash = normalizer.hash(normalized);
            String key = contact.identifierType() + ":" + hash;
            uniqueContacts.putIfAbsent(key, new ContactIdentifierRequest(contact.identifierType(), normalized));
            if (!contactRepository.existsByOwnerUserIdAndIdentifierTypeAndIdentifierHash(ownerId,
                    contact.identifierType(), hash)) {
                contactRepository.save(new com.uvya.apigateway.user.domain.ContactIdentifierEntity(ownerId,
                        contact.identifierType(), hash, Instant.now()));
            }
        }
        List<ContactMatchResponse> matches = new ArrayList<>();
        for (ContactIdentifierRequest contact : uniqueContacts.values()) {
            String normalized = contact.identifier();
            String hash = normalizer.hash(normalized);
            UserEntity matched = findMatch(contact.identifierType(), normalized, hash);
            if (matched == null || matched.getId().equals(ownerId) || matched.getStatus() != UserStatus.ACTIVE
                    || blocked(ownerId, matched.getId()) || blocked(matched.getId(), ownerId)) {
                continue;
            }
            UserProfileEntity profile = profileRepository.findByUserId(matched.getId()).orElse(null);
            if (profile != null && (profile.getDiscoverability() == Discoverability.NOBODY
                    || !contactMatchingAllowed(profile))) {
                continue;
            }
            boolean mutual = ownerHasIdentifier(matched.getId(), contact.identifierType(), owner);
            matches.add(new ContactMatchResponse(matched.getId(), profile == null ? null : profile.getUsername(),
                    profile == null ? null : profile.getDisplayName(),
                    profile == null ? emptyObject() : copy(profile.getAvatarMetadata()), mutual));
        }
        auditService.record("CONTACTS_UPLOADED", ownerId, null, null, context);
        return new ContactUploadResponse(matches);
    }

    @Transactional
    public void block(UUID blockerId, UUID blockedId, RequestContext context) {
        user(blockedId);
        blockedUserService.block(blockerId, blockedId);
        profileCache.evict(blockedId);
        auditService.record("USER_BLOCKED", blockerId, null, null, context);
    }

    @Transactional
    public void unblock(UUID blockerId, UUID blockedId, RequestContext context) {
        user(blockedId);
        blockedUserService.unblock(blockerId, blockedId);
        profileCache.evict(blockedId);
        auditService.record("USER_UNBLOCKED", blockerId, null, null, context);
    }

    private UserEntity findMatch(ContactIdentifierType type, String normalized, String hash) {
        if (type == ContactIdentifierType.EMAIL) {
            return userRepository.findByEmail(normalized).orElse(null);
        }
        return identifierRepository.findFirstByIdentifierTypeAndIdentifierHash(type, hash)
                .flatMap(identifier -> userRepository.findById(identifier.getUserId())).orElse(null);
    }

    private boolean ownerHasIdentifier(UUID targetId, ContactIdentifierType type, UserEntity owner) {
        String normalized = type == ContactIdentifierType.EMAIL ? owner.getEmail() : null;
        if (normalized == null) {
            return false;
        }
        return contactRepository.existsByOwnerUserIdAndIdentifierTypeAndIdentifierHash(targetId, type,
                normalizer.hash(normalizer.normalize(type, normalized)));
    }

    private boolean hasContactFor(UUID viewerId, UserEntity target) {
        String emailHash = normalizer.hash(normalizer.normalize(ContactIdentifierType.EMAIL, target.getEmail()));
        if (contactRepository.existsByOwnerUserIdAndIdentifierTypeAndIdentifierHash(viewerId,
                ContactIdentifierType.EMAIL, emailHash)) {
            return true;
        }
        return identifierRepository.findByUserIdAndIdentifierType(target.getId(), ContactIdentifierType.PHONE)
                .map(UserIdentifierEntity::getIdentifierHash)
                .map(hash -> contactRepository.existsByOwnerUserIdAndIdentifierTypeAndIdentifierHash(viewerId,
                        ContactIdentifierType.PHONE, hash)).orElse(false);
    }

    private boolean blocked(UUID first, UUID second) {
        return blockedUserService.isBlocked(first, second) || blockedUserService.isBlocked(second, first);
    }

    private boolean contactMatchingAllowed(UserProfileEntity profile) {
        JsonNode setting = profile.getPrivacySettings();
        return setting == null || !setting.has("allowContactMatching")
                || setting.get("allowContactMatching").asBoolean(true);
    }

    private UserEntity user(UUID userId) {
        return userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    }

    private UserProfileEntity profile(UUID userId) {
        return profileRepository.findByUserId(userId).orElseGet(() -> profileRepository.save(new UserProfileEntity(
                userId, emptyObject(), defaultPrivacy(), Instant.now())));
    }

    private UserProfileResponse privateResponse(UserEntity user, UserProfileEntity profile) {
        return new UserProfileResponse(user.getId(), user.getEmail(), profile.getUsername(), profile.getDisplayName(),
                profile.getBio(), copy(profile.getAvatarMetadata()), copy(profile.getPrivacySettings()),
                user.getStatus(), profile.getDiscoverability());
    }

    private UserProfileResponse publicResponse(UserEntity user, UserProfileEntity profile) {
        return new UserProfileResponse(user.getId(), null, profile.getUsername(), profile.getDisplayName(),
                profile.getBio(), copy(profile.getAvatarMetadata()), null, user.getStatus(),
                profile.getDiscoverability());
    }

    private void validateJson(JsonNode node) {
        if (node != null && (!node.isObject() || node.toString().length() > 32768)) {
            throw new UserServiceException("Invalid profile metadata");
        }
    }

    private JsonNode copy(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }

    private ObjectNode emptyObject() {
        return objectMapper.createObjectNode();
    }

    private ObjectNode defaultPrivacy() {
        ObjectNode privacy = objectMapper.createObjectNode();
        privacy.put("showEmail", false);
        privacy.put("allowContactMatching", true);
        return privacy;
    }
}
