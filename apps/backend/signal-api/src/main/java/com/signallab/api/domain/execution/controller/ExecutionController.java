package com.signallab.api.domain.execution.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.execution.service.ManualExecutionService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import com.signallab.api.domain.execution.dto.ManualExecutionRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/portfolios")
public class ExecutionController {

    private final ManualExecutionService manualExecutionService;
    private final DatabaseHealthService databaseHealthService;

    public ExecutionController(ManualExecutionService manualExecutionService, DatabaseHealthService databaseHealthService) {
        this.manualExecutionService = manualExecutionService;
        this.databaseHealthService = databaseHealthService;
    }

    @PostMapping("/{portfolioId}/executions")
    public Map<String, Object> register(
        @CurrentUser String userId,
        @PathVariable UUID portfolioId,
        @Valid @RequestBody ManualExecutionRequest request
    ) {
        return ApiEnvelope.ok(manualExecutionService.register(parseUserId(userId), portfolioId, request), databaseHealthService.isMockMode());
    }

    private UUID parseUserId(String userId) {
        try { return UUID.fromString(userId); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception); }
    }
}
