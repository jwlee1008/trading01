package com.signallab.api.domain.alert.controller;

import com.signallab.api.domain.alert.service.AlertSettingsService;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class AlertSettingsController {

    private final AlertSettingsService alertSettingsService;
    private final DatabaseHealthService databaseHealthService;

    public AlertSettingsController(AlertSettingsService alertSettingsService, DatabaseHealthService databaseHealthService) {
        this.alertSettingsService = alertSettingsService;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/alert-settings")
    public Map<String, Object> settings(@CurrentUser String userId) {
        return ApiEnvelope.ok(alertSettingsService.findFor(userId), databaseHealthService.isMockMode());
    }

    @PutMapping("/alert-settings")
    public Map<String, Object> updateSettings(@CurrentUser String userId, @RequestBody AlertSettingsService.Settings input) {
        return ApiEnvelope.ok(alertSettingsService.update(userId, input), databaseHealthService.isMockMode());
    }
}
