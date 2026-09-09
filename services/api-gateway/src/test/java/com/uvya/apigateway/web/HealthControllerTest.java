package com.uvya.apigateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void livenessReturnsUpAndPreservesSafeRequestId() throws Exception {
        mockMvc.perform(get("/health/live").header(RequestIdFilter.HEADER_NAME, "test-request-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "test-request-123"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.probe").value("liveness"));
    }

    @Test
    void readinessReturnsUpAndGeneratesRequestIdWhenMissing() throws Exception {
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.probe").value("readiness"));
    }

    @Test
    void unsafeRequestIdIsReplaced() throws Exception {
        mockMvc.perform(get("/health/live").header(RequestIdFilter.HEADER_NAME, "not safe\nvalue"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME,
                        org.hamcrest.Matchers.not("not safe\nvalue")));
    }
}
