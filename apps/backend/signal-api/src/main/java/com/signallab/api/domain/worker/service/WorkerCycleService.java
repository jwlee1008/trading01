package com.signallab.api.domain.worker.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkerCycleService {
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
        state.put("pendingOutbox", jdbc.queryForObject("SELECT count(*) FROM push_outbox WHERE status IN ('PENDING','FAILED')", Integer.class));
        state.put("pendingPaperOrders", jdbc.queryForObject("SELECT count(*) FROM paper_orders WHERE status='PENDING'", Integer.class));
        state.put("latestCandleSession", jdbc.query("SELECT max(session_date) FROM candles WHERE is_final AND NOT is_stale", rs -> rs.next() && rs.getDate(1) != null ? rs.getDate(1).toLocalDate().toString() : null));
        return state;
    }
}
