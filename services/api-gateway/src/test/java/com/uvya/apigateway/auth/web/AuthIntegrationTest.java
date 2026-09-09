package com.uvya.apigateway.auth.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uvya.apigateway.auth.service.AuthRateLimiter;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AuthRateLimiter rateLimiter;

    @Test
    void registrationReturnsAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
        MvcResult result = register(uniqueEmail());
        JsonNode response = json(result);

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(201);
        org.assertj.core.api.Assertions.assertThat(response.get("accessToken").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("HttpOnly").contains("SameSite=Strict");
    }

    @Test
    void invalidCredentialsAndUnknownAccountsAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        register(email);
        String known = login(email, "wrong-password").andReturn().getResponse().getContentAsString();
        String unknown = login(uniqueEmail(), "wrong-password").andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(known).isEqualTo(unknown);
    }

    @Test
    void repeatedInvalidCredentialsLockTheAccount() throws Exception {
        String email = uniqueEmail();
        register(email);
        for (int attempt = 0; attempt < 5; attempt++) {
            login(email, "wrong-password").andExpect(status().isUnauthorized());
        }

        login(email, "correct-password").andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotationAndReplayProtectionRevokeTheReplacement() throws Exception {
        MvcResult registration = register(uniqueEmail());
        Cookie oldCookie = refreshCookie(registration);
        MvcResult rotated = mockMvc.perform(post("/v1/auth/refresh").cookie(oldCookie).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        Cookie newCookie = refreshCookie(rotated);

        mockMvc.perform(post("/v1/auth/refresh").cookie(oldCookie).with(csrf()))
                .andExpect(status().isUnauthorized());
        String newAccessToken = json(rotated).get("accessToken").asText();
        mockMvc.perform(get("/v1/auth/sessions").header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isUnauthorized());
        org.assertj.core.api.Assertions.assertThat(newCookie.getValue()).isNotEqualTo(oldCookie.getValue());
    }

    @Test
    void cookieRefreshRequiresCsrfProtection() throws Exception {
        MvcResult registration = register(uniqueEmail());

        mockMvc.perform(post("/v1/auth/refresh").cookie(refreshCookie(registration)))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRevokesTheRefreshSessionAndAccessToken() throws Exception {
        MvcResult registration = register(uniqueEmail());
        Cookie refreshCookie = refreshCookie(registration);
        String accessToken = json(registration).get("accessToken").asText();

        mockMvc.perform(post("/v1/auth/logout").cookie(refreshCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/v1/auth/sessions").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserCannotRevokeAnotherUsersSession() throws Exception {
        MvcResult first = register(uniqueEmail());
        MvcResult second = register(uniqueEmail());
        UUID secondSession = UUID.fromString(json(second).get("sessionId").asText());
        String firstAccess = json(first).get("accessToken").asText();

        mockMvc.perform(delete("/v1/auth/sessions/{id}", secondSession)
                        .header("Authorization", "Bearer " + firstAccess).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokingOwnSessionInvalidatesItsAccessToken() throws Exception {
        MvcResult registration = register(uniqueEmail());
        UUID sessionId = UUID.fromString(json(registration).get("sessionId").asText());
        String accessToken = json(registration).get("accessToken").asText();

        mockMvc.perform(delete("/v1/auth/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + accessToken).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/v1/auth/sessions").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedJwtIsRejected() throws Exception {
        mockMvc.perform(get("/v1/auth/sessions").header("Authorization", "Bearer definitely.not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deviceRegistrationRequiresAnAuthenticatedOwner() throws Exception {
        mockMvc.perform(post("/v1/auth/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\":\"Phone\"}").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult register(String email) throws Exception {
        return mockMvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, "correct-password", "Laptop", null))))
                .andExpect(status().isCreated()).andReturn();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password, "Laptop", null))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Cookie refreshCookie(MvcResult result) {
        String value = result.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        return new Cookie("uvya_refresh", value);
    }

    private String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
