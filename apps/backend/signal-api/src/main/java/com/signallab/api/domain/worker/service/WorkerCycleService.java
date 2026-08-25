package com.signallab.api.domain.worker.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerCycleService {
    private static final Set<String> TASKS = Set.of("market-data", "signal", "sell-signal", "notification");
    private final JdbcTemplate jdbc;

    public WorkerCycleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> state() {
        List<Map<String, Object>> runs = jdbc.query("""
            SELECT task_name, run_key, status, attempt_count, started_at, finished_at, last_error
            FROM worker_task_runs ORDER BY started_at DESC LIMIT 100
            """, (rs, index) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskName", rs.getString("task_name")); row.put("runKey", rs.getString("run_key"));
                row.put("status", rs.getString("status")); row.put("attemptCount", rs.getInt("attempt_count"));
                row.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
                row.put("finishedAt", rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant().toString());
                row.put("lastError", rs.getString("last_error"));
                return row;
            });
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("database", "postgres");
        state.put("runs", runs);
        state.put("requests", recentRequests());
        state.put("pendingOutbox", jdbc.queryForObject("SELECT count(*) FROM push_outbox WHERE status IN ('PENDING','FAILED')", Integer.class));
        state.put("latestCandleSession", jdbc.query("SELECT max(session_date) FROM candles WHERE is_final AND NOT is_stale", rs -> rs.next() && rs.getDate(1) != null ? rs.getDate(1).toLocalDate().toString() : null));
        state.put("marketDataQuality", marketDataQuality());
        return state;
    }

    public Map<String, Object> request(String taskName) {
        return request(taskName, null);
    }

    @Transactional
    public Map<String, Object> request(String taskName, UUID requestedBy) {
        requireTask(taskName);
        Map<String, Object> existing = jdbc.query("""
            SELECT id,task_name,status,run_key FROM worker_task_requests
            WHERE task_name=? AND status IN ('PENDING','RUNNING') ORDER BY requested_at LIMIT 1
            """, rs -> rs.next() ? Map.of("requestId", rs.getString("id"), "taskName", rs.getString("task_name"),
                "status", rs.getString("status"), "runKey", rs.getString("run_key"), "alreadyQueued", true) : null, taskName);
        if (existing != null) return existing;
        UUID requestId = UUID.randomUUID();
        String runKey = "manual:" + requestId;
        jdbc.update("""
            INSERT INTO worker_task_requests(id,task_name,status,requested_by,run_key)
            VALUES (?,?,'PENDING',?,?)
            """, requestId, taskName, requestedBy, runKey);
        return Map.of("requestId", requestId, "taskName", taskName, "status", "PENDING", "runKey", runKey, "alreadyQueued", false);
    }

    public Map<String, Object> retry(UUID runId) {
        RunRow run = jdbc.query("""
            SELECT id,task_name,status FROM worker_task_runs WHERE id=?
            """, rs -> rs.next() ? new RunRow(UUID.fromString(rs.getString("id")), rs.getString("task_name"), rs.getString("status")) : null, runId);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker 실행 기록을 찾을 수 없습니다.");
        if (!"FAILED".equals(run.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "실패한 작업만 재실행할 수 있습니다.");
        requireTask(run.taskName());
        UUID requestId = UUID.randomUUID();
        String runKey = "retry:" + run.id() + ":" + requestId;
        jdbc.update("""
            INSERT INTO worker_task_requests(id,task_name,status,source_run_id,run_key)
            VALUES (?,?,'PENDING',?,?)
            """, requestId, run.taskName(), run.id(), runKey);
        return Map.of("requestId", requestId, "taskName", run.taskName(), "status", "PENDING", "runKey", runKey);
    }

    private List<Map<String, Object>> recentRequests() {
        return jdbc.query("""
            SELECT id,task_name,status,source_run_id,requested_at,started_at,finished_at,last_error
            FROM worker_task_requests ORDER BY requested_at DESC LIMIT 50
            """, (rs, index) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id")); row.put("taskName", rs.getString("task_name"));
                row.put("status", rs.getString("status")); row.put("sourceRunId", rs.getString("source_run_id"));
                row.put("requestedAt", rs.getTimestamp("requested_at").toInstant().toString());
                row.put("startedAt", instant(rs.getTimestamp("started_at")));
                row.put("finishedAt", instant(rs.getTimestamp("finished_at")));
                row.put("lastError", rs.getString("last_error"));
                return row;
            });
    }

    private Map<String, Object> marketDataQuality() {
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("instrumentCount", count("SELECT count(*) FROM instruments WHERE delisted_on IS NULL"));
        quality.put("tradeSuspendedCount", count("SELECT count(*) FROM instruments WHERE is_trade_suspended"));
        quality.put("finalCandleCount", count("SELECT count(*) FROM candles WHERE is_final AND NOT is_stale"));
        quality.put("staleCandleCount", count("SELECT count(*) FROM candles WHERE is_stale"));
        quality.put("nextTradingSession", jdbc.query("SELECT min(session_date) FROM market_sessions WHERE is_trading_day AND open_at>now()", rs -> rs.next() && rs.getDate(1) != null ? rs.getDate(1).toLocalDate().toString() : null));
        quality.put("missingLatestCandleCount", count("""
            WITH latest AS (SELECT max(session_date) d FROM candles WHERE is_final AND NOT is_stale),
            active_members AS (
              SELECT DISTINCT um.instrument_id FROM universe_memberships um
              JOIN universe_versions uv ON uv.id=um.universe_version_id
              WHERE uv.finalized_at IS NOT NULL AND uv.source<>'local-test-fixture'
                AND (uv.effective_to IS NULL OR uv.effective_to>=current_date)
            )
            SELECT count(*) FROM active_members am CROSS JOIN latest l
            WHERE l.d IS NOT NULL AND NOT EXISTS (
              SELECT 1 FROM candles c WHERE c.instrument_id=am.instrument_id AND c.session_date=l.d AND c.is_final AND NOT c.is_stale
            )
            """));
        return quality;
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static String instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static void requireTask(String taskName) {
        if (!TASKS.contains(taskName)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 Worker 작업입니다.");
    }

    private record RunRow(UUID id, String taskName, String status) {}
}
