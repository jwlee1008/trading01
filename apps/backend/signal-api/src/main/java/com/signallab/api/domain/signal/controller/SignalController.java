package com.signallab.api.domain.signal.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.signal.service.SignalService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/signals")
public class SignalController {

    private final SignalService signalService;
    private final DatabaseHealthService databaseHealthService;

    public SignalController(SignalService signalService, DatabaseHealthService databaseHealthService) {
        this.signalService = signalService;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping
    public Map<String, Object> list(@CurrentUser String userId, @RequestParam(required = false) String type) {
        String normalized = "BUY".equals(type) ? "BUY_CONDITION" : "SELL".equals(type) ? "SELL_CONDITION" : type;
        return ApiEnvelope.ok(signalService.findByUserId(parseUserId(userId), normalized), databaseHealthService.isMockMode());
    }

    @GetMapping("/{signalId}")
    public Map<String, Object> detail(@CurrentUser String userId, @PathVariable UUID signalId) {
        return ApiEnvelope.ok(signalService.findById(parseUserId(userId), signalId), databaseHealthService.isMockMode());
    }

    @PatchMapping("/{signalId}/acknowledge")
    public Map<String, Object> acknowledge(@CurrentUser String userId, @PathVariable UUID signalId) {
        return ApiEnvelope.ok(signalService.acknowledge(parseUserId(userId), signalId), databaseHealthService.isMockMode());
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception);
        }
    }
}
