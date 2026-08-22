package com.signallab.api.domain.worker;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "signal.auth-mode=mock",
    "signal.data-store=mock",
    "signal.worker-service-token=worker-secret"
})
class WorkerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void workerCycleRequiresItsOwnServiceTokenAndDeduplicatesSignals() throws Exception {
        String request = """
            {"cycleId":"cycle-1","userId":"demo-user","quotes":[{"symbol":"005930","sessionDate":"2026-08-14","officialOpen":"79000","close":"79200","volume":100,"tradeable":true,"dataStatus":"FRESH"}],"signals":[{"key":"signal-key-1","strategyVersionId":"sv-demo-1","symbol":"005930","candleClose":"2026-08-14","evidence":{"rsi":36.4}}]}
            """;
        mockMvc.perform(post("/v1/internal/worker/cycle").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/internal/worker/cycle").header("x-worker-service-token", "worker-secret")
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.signalsInserted").value(1));
        mockMvc.perform(post("/v1/internal/worker/cycle").header("x-worker-service-token", "worker-secret")
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.signalsDeduplicated").value(1));
        mockMvc.perform(get("/v1/internal/worker/state").header("x-worker-service-token", "worker-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.outbox[0].state").value("PENDING"));
    }
}
