package com.signallab.api.domain.strategy.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.strategy.service.StrategyService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import com.signallab.api.domain.strategy.dto.StrategyRequest;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/strategies")
public class StrategyController {

    private final StrategyService strategyService;
    private final DatabaseHealthService databaseHealthService;

    public StrategyController(StrategyService strategyService, DatabaseHealthService databaseHealthService) {
        this.strategyService = strategyService;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping
    public Map<String, Object> list(@CurrentUser String userId) {
        return ApiEnvelope.ok(strategyService.findByUserId(parseUserId(userId)), databaseHealthService.isPostgres());
    }

    @PostMapping
    public Map<String, Object> create(@CurrentUser String userId, @Valid @RequestBody StrategyRequest request) {
        return ApiEnvelope.ok(strategyService.create(parseUserId(userId), request), databaseHealthService.isPostgres());
    }

    @PostMapping("/{strategyId}/versions")
    public Map<String, Object> revise(
        @CurrentUser String userId,
        @PathVariable UUID strategyId,
        @Valid @RequestBody StrategyRequest request
    ) {
        return ApiEnvelope.ok(strategyService.revise(parseUserId(userId), strategyId, request), databaseHealthService.isPostgres());
    }

    @DeleteMapping("/{strategyId}")
    public Map<String, Object> delete(@CurrentUser String userId, @PathVariable UUID strategyId) {
        strategyService.delete(parseUserId(userId), strategyId);
        return ApiEnvelope.ok(Map.of("deleted", true), databaseHealthService.isPostgres());
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception);
        }
    }
}
