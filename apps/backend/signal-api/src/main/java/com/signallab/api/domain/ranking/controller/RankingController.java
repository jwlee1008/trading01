package com.signallab.api.domain.ranking.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.ranking.service.RankingService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class RankingController {

    private final RankingService rankingService;
    private final DatabaseHealthService databaseHealthService;
    public RankingController(RankingService rankingService, DatabaseHealthService databaseHealthService) {
        this.rankingService = rankingService;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/rankings")
    public Map<String, Object> rankings(@RequestParam(required = false) String period) {
        return ApiEnvelope.ok(rankingService.rankings(period), databaseHealthService.isPostgres());
    }

    @PostMapping("/profiles/{publicProfileId}/strategies/{strategyVersionId}/copy")
    public Map<String, Object> copyPublicStrategy(
        @CurrentUser String userId,
        @PathVariable UUID publicProfileId,
        @PathVariable UUID strategyVersionId
    ) {
        return ApiEnvelope.ok(
            rankingService.copyPublicStrategy(parseUserId(userId), publicProfileId, strategyVersionId),
            databaseHealthService.isPostgres()
        );
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception);
        }
    }
}
