package com.signallab.api.domain.ranking.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.ranking.service.RankingService;
import com.signallab.api.domain.ranking.service.RankingTrackService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/v1")
public class RankingController {

    private final RankingService rankingService;
    private final DatabaseHealthService databaseHealthService;
    private final RankingTrackService rankingTrackService;

    public RankingController(RankingService rankingService, DatabaseHealthService databaseHealthService, RankingTrackService rankingTrackService) {
        this.rankingService = rankingService;
        this.databaseHealthService = databaseHealthService;
        this.rankingTrackService = rankingTrackService;
    }

    @GetMapping("/me/ranking-track")
    public Map<String, Object> activeTrack(@CurrentUser String userId) {
        return ApiEnvelope.ok(rankingTrackService.active(parseUserId(userId)), databaseHealthService.isPostgres());
    }

    @PostMapping("/ranking-tracks")
    public Map<String, Object> startTrack(@CurrentUser String userId, @RequestBody RankingTrackService.StartRequest request) {
        return ApiEnvelope.ok(rankingTrackService.start(parseUserId(userId), request), databaseHealthService.isPostgres());
    }

    @DeleteMapping("/me/ranking-track")
    public Map<String, Object> endTrack(@CurrentUser String userId) {
        rankingTrackService.end(parseUserId(userId));
        return ApiEnvelope.ok(Map.of("ended", true), databaseHealthService.isPostgres());
    }

    @GetMapping("/rankings")
    public Map<String, Object> rankings(@RequestParam(required = false) String period) {
        return ApiEnvelope.ok(rankingService.rankings(period), databaseHealthService.isPostgres());
    }

    @PostMapping("/rankings/combinations/{combinationId}/copy")
    public Map<String, Object> copyCombination(
        @CurrentUser String userId,
        @PathVariable String combinationId
    ) {
        return ApiEnvelope.ok(
            rankingService.copyCombination(parseUserId(userId), combinationId),
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
