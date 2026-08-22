package com.signallab.api.domain.ranking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "signal.auth-mode=mock",
    "signal.data-store=mock",
    "signal.dev-auth-token=uuid-token",
    "signal.dev-auth-user-id=00000000-0000-0000-0000-000000000001"
})
class RankingAndProfileContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rankedCombinationCanBeCopiedIntoPrivateStrategy() throws Exception {
        mockMvc.perform(post("/v1/rankings/combinations/combo-3/copy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer uuid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("볼린저·RSI 복사본"))
            .andExpect(jsonPath("$.data.isPublic").value(false))
            .andExpect(jsonPath("$.data.rules.length()").value(2));
    }

    @Test
    void unknownRankedCombinationIsRejected() throws Exception {
        mockMvc.perform(post("/v1/rankings/combinations/missing/copy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer uuid-token"))
            .andExpect(status().isNotFound());
    }

    @Test
    void publicProfileReportValidatesAndReturnsContract() throws Exception {
        mockMvc.perform(post("/v1/profiles/00000000-0000-0000-0000-000000000002/reports")
                .header(HttpHeaders.AUTHORIZATION, "Bearer uuid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"부적절한 공개 프로필입니다\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reporterId").value("00000000-0000-0000-0000-000000000001"))
            .andExpect(jsonPath("$.data.targetUserId").value("00000000-0000-0000-0000-000000000002"));

        mockMvc.perform(post("/v1/profiles/00000000-0000-0000-0000-000000000002/reports")
                .header(HttpHeaders.AUTHORIZATION, "Bearer uuid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}"))
            .andExpect(status().isBadRequest());
    }
}
