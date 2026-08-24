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
            SELECT latest.last_candle_at,latest.last_session,latest.provider,
                   expected.session_date expected_session,next_session.close_at next_evaluation_at,
                   coverage.active_count,coverage.covered_count
            FROM (SELECT max(close_at) last_candle_at,max(session_date) last_session,
                         (array_agg(provider ORDER BY session_date DESC,received_at DESC))[1] provider
                  FROM candles WHERE is_final AND NOT is_stale) latest
            CROSS JOIN LATERAL (SELECT max(session_date) session_date FROM market_sessions
                                WHERE is_trading_day AND close_at<=now()) expected
            CROSS JOIN LATERAL (
              SELECT count(DISTINCT um.instrument_id) active_count,
                     count(DISTINCT c.instrument_id) FILTER (WHERE c.id IS NOT NULL) covered_count
              FROM universe_memberships um JOIN universe_versions uv ON uv.id=um.universe_version_id
              LEFT JOIN candles c ON c.instrument_id=um.instrument_id AND c.session_date=expected.session_date
                                  AND c.is_final AND NOT c.is_stale
              WHERE uv.finalized_at IS NOT NULL AND (uv.effective_to IS NULL OR uv.effective_to>=current_date)
            ) coverage
            LEFT JOIN LATERAL (SELECT close_at FROM market_sessions WHERE is_trading_day AND close_at>now()
                               ORDER BY close_at LIMIT 1) next_session ON true
            """, rs -> rs.next() ? new ProviderRow(
                rs.getTimestamp("last_candle_at") == null ? null : rs.getTimestamp("last_candle_at").toInstant().toString(),
                rs.getDate("last_session") == null ? null : rs.getDate("last_session").toLocalDate(),
                rs.getDate("expected_session") == null ? null : rs.getDate("expected_session").toLocalDate(),
                rs.getString("provider"),
                rs.getTimestamp("next_evaluation_at") == null ? null : rs.getTimestamp("next_evaluation_at").toInstant().toString(),
                rs.getInt("active_count"), rs.getInt("covered_count")
            ) : null);
        boolean disconnected = row == null || row.lastSession() == null;
        boolean delayed = !disconnected && ((row.expectedSession() != null && row.lastSession().isBefore(row.expectedSession()))
            || row.coveredInstrumentCount() < row.activeInstrumentCount());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", row == null || row.provider() == null ? "unavailable" : row.provider());
        payload.put("state", disconnected ? "DISCONNECTED" : delayed ? "DEGRADED" : "CONNECTED");
        payload.put("lastCandleAt", row == null ? null : row.lastCandleAt());
        payload.put("lastSession", row == null || row.lastSession() == null ? null : row.lastSession().toString());
        payload.put("expectedSession", row == null || row.expectedSession() == null ? null : row.expectedSession().toString());
        payload.put("nextEvaluationAt", row == null ? null : row.nextEvaluationAt());
        payload.put("delayed", delayed);
        payload.put("activeInstrumentCount", row == null ? 0 : row.activeInstrumentCount());
        payload.put("coveredInstrumentCount", row == null ? 0 : row.coveredInstrumentCount());
        return ApiEnvelope.ok(payload, databaseHealthService.isPostgres());
    }

    private record ProviderRow(String lastCandleAt, java.time.LocalDate lastSession,
                               java.time.LocalDate expectedSession, String provider, String nextEvaluationAt,
                               int activeInstrumentCount, int coveredInstrumentCount) {}
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
