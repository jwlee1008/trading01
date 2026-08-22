package com.signallab.api.domain.order.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.order.service.SellRuleService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import com.signallab.api.domain.order.dto.SellRuleRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/positions")
public class PositionSellRuleController {

    private final SellRuleService sellRuleService;
    private final DatabaseHealthService databaseHealthService;

    public PositionSellRuleController(SellRuleService sellRuleService, DatabaseHealthService databaseHealthService) {
        this.sellRuleService = sellRuleService;
        this.databaseHealthService = databaseHealthService;
    }

    @PostMapping("/{positionId}/sell-rules")
    public Map<String, Object> save(@CurrentUser String userId, @PathVariable UUID positionId, @RequestBody SellRuleRequest request) {
        return ApiEnvelope.ok(sellRuleService.save(parseUserId(userId), positionId, request), databaseHealthService.isMockMode());
    }

    private static UUID parseUserId(String userId) {
        try { return UUID.fromString(userId); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception); }
    }
}
