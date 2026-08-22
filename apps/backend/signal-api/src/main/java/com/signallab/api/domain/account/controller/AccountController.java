package com.signallab.api.domain.account.controller;

import com.signallab.api.domain.account.service.AccountService;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class AccountController {

    private final AccountService accountService;
    private final DatabaseHealthService databaseHealthService;

    public AccountController(AccountService accountService, DatabaseHealthService databaseHealthService) {
        this.accountService = accountService;
        this.databaseHealthService = databaseHealthService;
    }

    @DeleteMapping("/me")
    public Map<String, Object> deleteAccount(@CurrentUser String userId) {
        accountService.delete(userId);
        return ApiEnvelope.ok(Map.of("accepted", true, "deletedAt", Instant.now().toString()), databaseHealthService.isPostgres());
    }
}
