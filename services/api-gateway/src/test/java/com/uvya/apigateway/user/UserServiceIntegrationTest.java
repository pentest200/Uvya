package com.uvya.apigateway.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.uvya.apigateway.auth.domain.UserEntity;
import com.uvya.apigateway.auth.repository.AuthAuditLogRepository;
import com.uvya.apigateway.auth.repository.UserRepository;
import com.uvya.apigateway.auth.service.AuthRateLimiter;
import com.uvya.apigateway.user.cache.UserProfileCache;
import com.uvya.apigateway.user.domain.Discoverability;
import com.uvya.apigateway.user.repository.ContactIdentifierRepository;
import com.uvya.apigateway.user.web.UserProfileResponse;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserServiceIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ContactIdentifierRepository contactRepository;
    @Autowired private AuthAuditLogRepository auditLogRepository;
    @MockBean private AuthRateLimiter rateLimiter;
    @MockBean private UserProfileCache profileCache;

    @Test
    void userCanCreateAndUpdateOwnProfileButCannotPatchAnotherUser() throws Exception {
        TestUser owner = register();
        TestUser other = register();

        mockMvc.perform(get("/v1/users/me").header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(owner.email()));
        mockMvc.perform(patch("/v1/users/me").with(csrf())
                .header("Authorization", bearer(owner.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner_123\",\"displayName\":\"Owner\","
                        + "\"bio\":\"private\",\"discoverability\":\"PUBLIC\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("owner_123"));
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.countByEventType("PROFILE_UPDATED")).isPositive();

        mockMvc.perform(patch("/v1/users/" + other.userId()).with(csrf())
                .header("Authorization", bearer(owner.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"attacker\"}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/v1/users/me").header("Authorization", bearer(other.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").doesNotExist());
    }

    @Test
    void blockedProfilesAreHiddenInBothDirectionsAndBecomeVisibleAfterUnblock() throws Exception {
        TestUser viewer = register();
        TestUser target = register();
        makePublic(target, "blocked_target");

        mockMvc.perform(post("/v1/users/" + target.userId() + "/block").with(csrf())
                .header("Authorization", bearer(viewer.accessToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/v1/users/" + target.userId())
                .header("Authorization", bearer(viewer.accessToken()))).andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/users/" + viewer.userId())
                .header("Authorization", bearer(target.accessToken()))).andExpect(status().isNotFound());

        mockMvc.perform(delete("/v1/users/" + target.userId() + "/block").with(csrf())
                .header("Authorization", bearer(viewer.accessToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/v1/users/" + target.userId())
                .header("Authorization", bearer(viewer.accessToken()))).andExpect(status().isOk());
    }

    @Test
    void contactUploadMatchesWithoutReturningRawIdentifiersAndDetectsMutualContacts() throws Exception {
        TestUser owner = register();
        TestUser target = register();
        makePublic(target, "contact_target");
        String contacts = "{\"contacts\":[{\"identifierType\":\"EMAIL\",\"identifier\":\""
                + target.email().toUpperCase() + "\"}]}";

        mockMvc.perform(post("/v1/users/contacts").with(csrf())
                .header("Authorization", bearer(owner.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content(contacts)).andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].userId").value(target.userId().toString()))
                .andExpect(jsonPath("$.matches[0].mutualContact").value(false))
                .andExpect(jsonPath("$.matches[0].email").doesNotExist())
                .andExpect(jsonPath("$.matches[0].identifier").doesNotExist());

        String reverse = "{\"contacts\":[{\"identifierType\":\"EMAIL\",\"identifier\":\""
                + owner.email() + "\"}]}";
        mockMvc.perform(post("/v1/users/contacts").with(csrf())
                .header("Authorization", bearer(target.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content(reverse)).andExpect(status().isOk());
        mockMvc.perform(post("/v1/users/contacts").with(csrf())
                .header("Authorization", bearer(owner.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content(contacts)).andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].mutualContact").value(true));
        org.assertj.core.api.Assertions.assertThat(contactRepository.findAll()).allSatisfy(contact ->
                org.assertj.core.api.Assertions.assertThat(contact.getIdentifierHash()).doesNotContain("@"));
    }

    @Test
    void publicProfileCacheSupportsMissPopulationAndHit() throws Exception {
        TestUser viewer = register();
        TestUser target = register();
        makePublic(target, "cache_target");
        when(profileCache.get(target.userId())).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/users/" + target.userId())
                .header("Authorization", bearer(viewer.accessToken()))).andExpect(status().isOk());
        verify(profileCache).put(any(UserProfileResponse.class));

        UserProfileResponse cached = new UserProfileResponse(target.userId(), null, "cache_target", "Cached",
                null, objectMapper.createObjectNode(), null,
                com.uvya.apigateway.auth.domain.UserStatus.ACTIVE, Discoverability.PUBLIC);
        when(profileCache.get(target.userId())).thenReturn(Optional.of(cached));
        mockMvc.perform(get("/v1/users/" + target.userId())
                .header("Authorization", bearer(viewer.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Cached"));
    }

    @Test
    void publicSearchIsPaginatedAndDoesNotExposePrivateFields() throws Exception {
        TestUser viewer = register();
        TestUser first = register();
        TestUser second = register();
        makePublic(first, "discover_one");
        makePublic(second, "discover_two");

        mockMvc.perform(get("/v1/users/search?q=discover&page=0&size=1")
                .header("Authorization", bearer(viewer.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.content[0].privacySettings").doesNotExist());
    }

    private void makePublic(TestUser user, String username) throws Exception {
        mockMvc.perform(patch("/v1/users/me").with(csrf())
                .header("Authorization", bearer(user.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"discoverability\":\"PUBLIC\"}"))
                .andExpect(status().isOk());
    }

    private TestUser register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.test";
        String body = "{\"email\":\"" + email + "\",\"password\":\"correct-password\","
                + "\"deviceName\":\"test-device\"}";
        MvcResult result = mockMvc.perform(post("/v1/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        return new TestUser(email, user.getId(), response.get("accessToken").asText());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record TestUser(String email, UUID userId, String accessToken) { }
}
