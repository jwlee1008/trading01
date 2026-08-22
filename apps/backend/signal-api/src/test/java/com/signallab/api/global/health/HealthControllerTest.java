package com.signallab.api.global.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
        "signal.auth-mode=mock",
        "signal.data-store=mock",
        "signal.dev-auth-token=demo-token",
        "signal.dev-auth-user-id=demo-user",
    }
)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsPublicAndReturnsEnvelope() throws Exception {
        mockMvc.perform(get("/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ok"))
            .andExpect(jsonPath("$.data.service").value("signal-api"))
            .andExpect(jsonPath("$.data.provider").value("mock"))
            .andExpect(jsonPath("$.data.database").value("memory"))
            .andExpect(jsonPath("$.data.ping").value("ok"))
            .andExpect(jsonPath("$.meta.mock").value(true))
            .andExpect(jsonPath("$.meta.requestId").exists())
            .andExpect(jsonPath("$.meta.generatedAt").exists());
    }

    @Test
    void protectedRouteRequiresBearerToken() throws Exception {
        mockMvc.perform(get("/v1/me/ping")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRouteAcceptsMockBearerToken() throws Exception {
        mockMvc.perform(get("/v1/me/ping").header(HttpHeaders.AUTHORIZATION, "Bearer demo-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value("demo-user"))
            .andExpect(jsonPath("$.meta.mock").value(true));
    }

    @Test
    void rankingsArePublicAndReturnTheRequestedPeriod() throws Exception {
        mockMvc.perform(get("/v1/rankings?period=6M"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.period").value("6M"))
            .andExpect(jsonPath("$.data.combinations[0].id").value("combo-1"));
    }
}
