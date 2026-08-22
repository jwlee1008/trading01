package com.signallab.api.domain.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "signal.auth-mode=mock",
    "signal.data-store=mock",
    "signal.dev-auth-token=demo-token",
    "signal.dev-auth-user-id=demo-user"
})
class AccountAndAlertSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void alertSettingsCanBeReadAndUpdatedInMockMode() throws Exception {
        mockMvc.perform(get("/v1/alert-settings").header(HttpHeaders.AUTHORIZATION, "Bearer demo-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.quietHoursEnabled").value(false));

        mockMvc.perform(put("/v1/alert-settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer demo-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"quietHoursEnabled\":true,\"quietStart\":\"21:30\",\"quietEnd\":\"08:15\",\"showPriceOnLockScreen\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quietStart").value("21:30"))
            .andExpect(jsonPath("$.data.showPriceOnLockScreen").value(true));
    }

    @Test
    void alertsUseTheExistingMockFixture() throws Exception {
        mockMvc.perform(get("/v1/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer demo-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].signalId").value("sig-buy-1"))
            .andExpect(jsonPath("$.data[1].read").value(true));
    }

    @Test
    void entitlementContractKeepsAllMvpFeaturesEnabled() throws Exception {
        mockMvc.perform(get("/v1/me/entitlements").header(HttpHeaders.AUTHORIZATION, "Bearer demo-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plan").value("MVP_FREE"))
            .andExpect(jsonPath("$.data.decisions.length()").value(5))
            .andExpect(jsonPath("$.data.decisions[0].allowed").value(true));
    }

    @Test
    @DirtiesContext
    void deletedMockAccountIsRejectedOnFollowingRequest() throws Exception {
        mockMvc.perform(delete("/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer demo-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accepted").value(true));

        mockMvc.perform(get("/v1/me/ping").header(HttpHeaders.AUTHORIZATION, "Bearer demo-token"))
            .andExpect(status().isGone());
    }
}
