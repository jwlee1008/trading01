package com.signallab.api.global.health.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import com.signallab.api.global.config.SignalProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

@RestController
@RequestMapping("/v1")
public class HealthController {

    private final DatabaseHealthService databaseHealthService;
    private final SignalProperties signalProperties;
    private final JdbcTemplate jdbc;

    public HealthController(DatabaseHealthService databaseHealthService, SignalProperties signalProperties, JdbcTemplate jdbc) {
        this.databaseHealthService = databaseHealthService;
        this.signalProperties = signalProperties;
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> payload = new LinkedHashMap<>(databaseHealthService.health());
        payload.put("status", "ok");
        payload.put("service", "signal-api");
        payload.put("clock", "virtual-ready");
        payload.put("ai", Map.of(
            "provider", "gemini",
            "configured", signalProperties.getGeminiApiKey() != null && !signalProperties.getGeminiApiKey().isBlank(),
            "model", signalProperties.getGeminiModel()
        ));
        return ApiEnvelope.ok(payload, databaseHealthService.isPostgres());
    }

    @GetMapping("/provider/status")
    public Map<String, Object> providerStatus() {
        ProviderRow row = jdbc.query("""
            SELECT max(c.close_at) last_candle_at, max(c.session_date) last_session,
                   max(ms.session_date) expected_session,
                   (array_agg(c.provider ORDER BY c.session_date DESC, c.received_at DESC))[1] provider
            FROM candles c CROSS JOIN (SELECT max(session_date) session_date FROM market_sessions WHERE is_trading_day) ms
            WHERE c.is_final AND NOT c.is_stale
            """, rs -> rs.next() ? new ProviderRow(
                rs.getTimestamp("last_candle_at") == null ? null : rs.getTimestamp("last_candle_at").toInstant().toString(),
                rs.getDate("last_session") == null ? null : rs.getDate("last_session").toLocalDate(),
                rs.getDate("expected_session") == null ? null : rs.getDate("expected_session").toLocalDate(),
                rs.getString("provider")
            ) : null);
        boolean disconnected = row == null || row.lastSession() == null;
        boolean delayed = !disconnected && row.expectedSession() != null && row.lastSession().isBefore(row.expectedSession());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", row == null || row.provider() == null ? "unavailable" : row.provider());
        payload.put("state", disconnected ? "DISCONNECTED" : delayed ? "DEGRADED" : "CONNECTED");
        payload.put("lastCandleAt", row == null ? null : row.lastCandleAt());
        payload.put("delayed", delayed);
        return ApiEnvelope.ok(payload, databaseHealthService.isPostgres());
    }

    private record ProviderRow(String lastCandleAt, java.time.LocalDate lastSession,
                               java.time.LocalDate expectedSession, String provider) {}
}

@RestController
@RequestMapping("/v1")
class AuthProbeController {

    private final DatabaseHealthService databaseHealthService;

    AuthProbeController(DatabaseHealthService databaseHealthService) {
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/me/ping")
    public Map<String, Object> ping(@CurrentUser String userId) {
        return ApiEnvelope.ok(Map.of("userId", userId), databaseHealthService.isPostgres());
    }
}
