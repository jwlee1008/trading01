package com.signallab.api.domain.advice.controller;

import com.signallab.api.domain.advice.service.AdviceService;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/signals")
public class AdviceController {

    private final AdviceService adviceService;
    private final DatabaseHealthService databaseHealthService;

    public AdviceController(AdviceService adviceService, DatabaseHealthService databaseHealthService) {
        this.adviceService = adviceService;
        this.databaseHealthService = databaseHealthService;
    }

    @PostMapping("/{signalId}/advice")
    public Map<String, Object> explain(@CurrentUser String userId, @PathVariable UUID signalId) {
        return ApiEnvelope.ok(adviceService.explain(parseUserId(userId), signalId), databaseHealthService.isMockMode());
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception);
        }
    }
}
