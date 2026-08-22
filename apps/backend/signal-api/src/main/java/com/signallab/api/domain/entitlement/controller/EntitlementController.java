package com.signallab.api.domain.entitlement.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.entitlement.service.EntitlementService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class EntitlementController {

    private final EntitlementService entitlementService;
    private final DatabaseHealthService databaseHealthService;

    public EntitlementController(EntitlementService entitlementService, DatabaseHealthService databaseHealthService) {
        this.entitlementService = entitlementService;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/me/entitlements")
    public Map<String, Object> entitlements(@CurrentUser String userId) {
        return ApiEnvelope.ok(entitlementService.forUser(userId), databaseHealthService.isMockMode());
    }
}
