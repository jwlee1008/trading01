package com.signallab.api.domain.profile.controller;

import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.profile.service.ProfileReportService;
import com.signallab.domain.profile.entity.Profile;
import com.signallab.domain.profile.repository.ProfileRepository;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class ProfileController {

    private final ProfileRepository profileRepository;
    private final DatabaseHealthService databaseHealthService;
    private final ProfileReportService profileReportService;

    public ProfileController(ProfileRepository profileRepository, DatabaseHealthService databaseHealthService,
                             ProfileReportService profileReportService) {
        this.profileRepository = profileRepository;
        this.databaseHealthService = databaseHealthService;
        this.profileReportService = profileReportService;
    }

    @GetMapping("/profiles/{userId}/public")
    public Map<String, Object> getPublicProfile(@PathVariable UUID userId) {
        return profileRepository.findByUserId(userId)
                .filter(Profile::isPublic)
                .map(profile -> ApiEnvelope.ok(profile, databaseHealthService.isMockMode()))
                .orElseThrow(() -> new RuntimeException("Profile not found or not public"));
    }

    @PutMapping("/me/visibility")
    public Map<String, Object> updateVisibility(
        @CurrentUser String userId,
        @RequestBody VisibilityRequest request
    ) {
        profileRepository.updateVisibility(parseUserId(userId), request.isPublic());
        return ApiEnvelope.ok(null, databaseHealthService.isMockMode());
    }

    @PostMapping("/profiles/{userId}/reports")
    public Map<String, Object> reportProfile(
        @CurrentUser String reporterId,
        @PathVariable UUID userId,
        @RequestBody ProfileReportService.ReportRequest request
    ) {
        return ApiEnvelope.ok(
            profileReportService.report(parseUserId(reporterId), userId, request),
            databaseHealthService.isMockMode()
        );
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception);
        }
    }

    public record VisibilityRequest(boolean isPublic) {}
}
