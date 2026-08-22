package com.signallab.api.global.health.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import com.signallab.api.global.config.SignalProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class HealthController {

    private final DatabaseHealthService databaseHealthService;
    private final SignalProperties signalProperties;

    public HealthController(DatabaseHealthService databaseHealthService, SignalProperties signalProperties) {
        this.databaseHealthService = databaseHealthService;
        this.signalProperties = signalProperties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> payload = new LinkedHashMap<>(databaseHealthService.health());
        payload.put("status", "ok");
        payload.put("service", "signal-api");
        payload.put("clock", "virtual-ready");
        payload.put("ai", Map.of(
            "provider", "gemini",
            "configured", signalProperties.getGeminiApiKey() != null && !signalProperties.getGeminiApiKey().isBlank(),
            "model", signalProperties.getGeminiModel()
        ));
        return ApiEnvelope.ok(payload, databaseHealthService.isMockMode());
    }

    @GetMapping("/provider/status")
    public Map<String, Object> providerStatus() {
        String provider = databaseHealthService.isMockMode() ? "mock" : "postgres";
        Map<String, Object> payload = Map.of(
            "provider", provider,
            "state", "CONNECTED",
            "lastCandleAt", "2026-08-14T06:30:00.000Z",
            "delayed", false,
            "scenarios", List.of(
                "MISSING",
                "DUPLICATE",
                "OUT_OF_ORDER",
                "DISCONNECT",
                "RATE_LIMIT",
                "TOKEN_EXPIRED"
            )
        );
        return ApiEnvelope.ok(payload, databaseHealthService.isMockMode());
    }
}

@RestController
@RequestMapping("/v1")
class AuthProbeController {

    private final DatabaseHealthService databaseHealthService;

    AuthProbeController(DatabaseHealthService databaseHealthService) {
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/me/ping")
    public Map<String, Object> ping(@CurrentUser String userId) {
        return ApiEnvelope.ok(Map.of("userId", userId), databaseHealthService.isMockMode());
    }
}
