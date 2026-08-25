package com.signallab.api.domain.signal.controller;

import com.signallab.api.domain.signal.service.TestTop30UniverseService;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me/test-top30")
public class TestTop30UniverseController {
    private final TestTop30UniverseService service;
    private final DatabaseHealthService databaseHealthService;

    public TestTop30UniverseController(TestTop30UniverseService service, DatabaseHealthService databaseHealthService) {
        this.service = service;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping
    public Map<String, Object> status(@CurrentUser String ignoredUserId) {
        return ApiEnvelope.ok(service.status(), databaseHealthService.isPostgres());
    }

    @PutMapping
    public Map<String, Object> configure(@CurrentUser String ignoredUserId, @RequestBody ConfigureRequest request) {
        return ApiEnvelope.ok(service.configure(request.fixtures()), databaseHealthService.isPostgres());
    }

    public record ConfigureRequest(List<TestTop30UniverseService.FixtureInput> fixtures) {}
}
