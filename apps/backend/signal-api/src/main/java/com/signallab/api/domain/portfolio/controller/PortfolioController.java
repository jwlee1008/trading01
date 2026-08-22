package com.signallab.api.domain.portfolio.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.portfolio.service.PortfolioQueryService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class PortfolioController {

    private final PortfolioQueryService portfolioQueryService;
    private final DatabaseHealthService databaseHealthService;

    public PortfolioController(PortfolioQueryService portfolioQueryService, DatabaseHealthService databaseHealthService) {
        this.portfolioQueryService = portfolioQueryService;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/portfolios")
    public Map<String, Object> portfolios(@CurrentUser String userId) {
        return ApiEnvelope.ok(portfolioQueryService.portfoliosFor(parseUserId(userId)), databaseHealthService.isPostgres());
    }

    @GetMapping("/positions")
    public Map<String, Object> positions(@CurrentUser String userId) {
        return ApiEnvelope.ok(portfolioQueryService.positionsFor(parseUserId(userId)), databaseHealthService.isPostgres());
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception);
        }
    }
}
