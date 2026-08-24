package com.signallab.api.domain.signal.controller;

import com.signallab.api.domain.signal.service.SignalTestFixtureService;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me/test-fixtures")
public class SignalTestFixtureController {
    private final SignalTestFixtureService service;
    private final DatabaseHealthService databaseHealthService;

    public SignalTestFixtureController(SignalTestFixtureService service, DatabaseHealthService databaseHealthService) {
        this.service = service;
        this.databaseHealthService = databaseHealthService;
    }

    @PostMapping("/buy-signal")
    public Map<String, Object> create(@CurrentUser String userId) {
        return ApiEnvelope.ok(service.createSignalScenario(UUID.fromString(userId)), databaseHealthService.isPostgres());
    }
}
