package com.signallab.api.domain.alert.controller;

import com.signallab.api.domain.alert.service.AlertService;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class AlertController {

    private final AlertService alertService;
    private final DatabaseHealthService databaseHealthService;

    public AlertController(AlertService alertService, DatabaseHealthService databaseHealthService) {
        this.alertService = alertService;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/alerts")
    public Map<String, Object> alerts(@CurrentUser String userId) {
        return ApiEnvelope.ok(alertService.findFor(userId), databaseHealthService.isMockMode());
    }
}
