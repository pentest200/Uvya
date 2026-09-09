package com.uvya.apigateway.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.uvya.apigateway.auth.service.AuthRateLimiter;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpiredAccessTokenIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtEncoder jwtEncoder;
    @MockBean private AuthRateLimiter rateLimiter;

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        Instant issuedAt = Instant.now().minusSeconds(600);
        String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("http://test.local")
                .subject(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(60))
                .build())).getTokenValue();

        mockMvc.perform(get("/v1/auth/sessions")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }
}
