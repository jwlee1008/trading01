package com.signallab.api.domain.worker.controller;

import com.signallab.api.domain.worker.service.WorkerCycleService;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me/market-data")
public class MarketDataRefreshController {
    private final WorkerCycleService workerCycleService;
    private final DatabaseHealthService databaseHealthService;

    public MarketDataRefreshController(WorkerCycleService workerCycleService, DatabaseHealthService databaseHealthService) {
        this.workerCycleService = workerCycleService;
        this.databaseHealthService = databaseHealthService;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@CurrentUser String userId) {
        return ApiEnvelope.ok(workerCycleService.request("market-data", UUID.fromString(userId)), databaseHealthService.isPostgres());
    }
}
