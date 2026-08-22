package com.signallab.api.domain.worker.controller;

import com.signallab.api.global.config.SignalProperties;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.worker.service.WorkerCycleService;
import com.signallab.api.global.web.ApiEnvelope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/internal/worker")
public class WorkerController {

    private final WorkerCycleService workerCycleService;
    private final SignalProperties properties;
    private final DatabaseHealthService databaseHealthService;

    public WorkerController(WorkerCycleService workerCycleService, SignalProperties properties,
                            DatabaseHealthService databaseHealthService) {
        this.workerCycleService = workerCycleService;
        this.properties = properties;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/state")
    public Map<String, Object> state(@RequestHeader(value = "x-worker-service-token", required = false) String token) {
        requireWorkerToken(token);
        return ApiEnvelope.ok(workerCycleService.state(), databaseHealthService.isPostgres());
    }

    private void requireWorkerToken(String provided) {
        String expected = properties.getWorkerServiceToken();
        if (expected == null || expected.isBlank() || provided == null || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "워커 서비스 토큰이 필요합니다.");
        }
    }
}
