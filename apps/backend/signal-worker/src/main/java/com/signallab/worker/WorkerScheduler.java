package com.signallab.worker;

import com.signallab.worker.domain.outbox.service.PostgresOutboxDispatcher;
import com.signallab.worker.domain.signal.service.PostgresDailySignalCycle;
import com.signallab.worker.domain.signal.service.PostgresSellSignalCycle;
import com.signallab.worker.domain.marketdata.service.MarketDataImportService;
import com.signallab.worker.global.config.WorkerProperties;
import com.signallab.worker.global.runtime.WorkerTaskRunner;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class WorkerScheduler {
    private final WorkerProperties properties;
    private final PostgresOutboxDispatcher outboxDispatcher;
    private final PostgresDailySignalCycle dailySignalCycle;
    private final PostgresSellSignalCycle sellSignalCycle;
    private final MarketDataImportService marketDataImportService;
    private final WorkerTaskRunner taskRunner;
    private final JdbcTemplate jdbc;

    WorkerScheduler(WorkerProperties properties, PostgresOutboxDispatcher outboxDispatcher, PostgresDailySignalCycle dailySignalCycle, PostgresSellSignalCycle sellSignalCycle, MarketDataImportService marketDataImportService, WorkerTaskRunner taskRunner, JdbcTemplate jdbc) {
        this.properties = properties;
        this.outboxDispatcher = outboxDispatcher;
        this.dailySignalCycle = dailySignalCycle;
        this.sellSignalCycle = sellSignalCycle;
        this.marketDataImportService = marketDataImportService;
        this.taskRunner = taskRunner;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${signal.worker.outbox-cron:0 */1 * * * *}", zone = "Asia/Seoul")
    void dispatchOutbox() {
        if (properties.isEnabled()) {
            String runKey = Instant.now().truncatedTo(ChronoUnit.MINUTES).toString();
            taskRunner.run("signal", runKey, () -> dailySignalCycle.run(properties));
            taskRunner.run("sell-signal", runKey, () -> sellSignalCycle.run(properties));
            taskRunner.run("notification", runKey, () -> outboxDispatcher.dispatch(properties));
        }
    }

    @Scheduled(initialDelayString = "${signal.worker.market-data-auto-initial-delay-ms:5000}",
        fixedDelayString = "${signal.worker.market-data-auto-interval-ms:300000}")
    void refreshMarketData() {
        if (properties.isEnabled() && properties.isMarketDataAutoEnabled()) {
            String runKey = Instant.now().truncatedTo(ChronoUnit.MINUTES).toString();
            taskRunner.run("market-data", runKey, () -> marketDataImportService.automaticRefresh(properties));
        }
    }

    @Scheduled(fixedDelayString = "${signal.worker.request-poll-interval-ms:2000}")
    void drainRequestedTasks() {
        if (!properties.isEnabled()) return;
        RequestedTask request = jdbc.query("""
            WITH next_request AS (
              SELECT id FROM worker_task_requests WHERE status='PENDING'
              ORDER BY requested_at FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE worker_task_requests r SET status='RUNNING',started_at=now()
            FROM next_request n WHERE r.id=n.id
            RETURNING r.id,r.task_name,r.run_key
            """, rs -> rs.next() ? new RequestedTask(
                UUID.fromString(rs.getString("id")), rs.getString("task_name"), rs.getString("run_key")) : null);
        if (request == null) return;

        Supplier<?> action = action(request.taskName());
        boolean succeeded = taskRunner.run(request.taskName(), request.runKey(), action);
        String error = succeeded ? null : jdbc.query("""
            SELECT last_error FROM worker_task_runs WHERE task_name=? AND run_key=?
            """, rs -> rs.next() ? rs.getString(1) : "작업 실행 기록을 찾을 수 없습니다.", request.taskName(), request.runKey());
        jdbc.update("""
            UPDATE worker_task_requests SET status=?,finished_at=now(),last_error=? WHERE id=?
            """, succeeded ? "SUCCEEDED" : "FAILED", error, request.id());
    }

    private Supplier<?> action(String taskName) {
        return switch (taskName) {
            case "market-data" -> () -> marketDataImportService.automaticRefresh(properties);
            case "signal" -> () -> dailySignalCycle.run(properties);
            case "sell-signal" -> () -> sellSignalCycle.run(properties);
            case "notification" -> () -> outboxDispatcher.dispatch(properties);
            default -> throw new IllegalArgumentException("지원하지 않는 Worker 작업입니다: " + taskName);
        };
    }

    private record RequestedTask(UUID id, String taskName, String runKey) {}
}
