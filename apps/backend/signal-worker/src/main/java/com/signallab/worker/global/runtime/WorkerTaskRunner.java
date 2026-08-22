package com.signallab.worker.global.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.worker.global.config.WorkerProperties;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkerTaskRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;

    public WorkerTaskRunner(JdbcTemplate jdbc, ObjectMapper objectMapper, WorkerProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void run(String taskName, String runKey, Supplier<?> task) {
        int claimed = jdbc.update("""
            INSERT INTO worker_task_runs(task_name,run_key,status,attempt_count,started_at,finished_at,last_error,result)
            VALUES (?,?,'RUNNING',0,now(),null,null,null)
            ON CONFLICT(task_name,run_key) DO UPDATE SET
              status='RUNNING',started_at=now(),finished_at=null,last_error=null,result=null
            WHERE worker_task_runs.status IN ('FAILED','SKIPPED')
               OR (worker_task_runs.status='RUNNING' AND worker_task_runs.started_at < now() - interval '10 minutes')
            """, taskName, runKey);
        if (claimed == 0) return;

        int maxAttempts = Math.max(1, Math.min(properties.getTaskMaxRetries(), 10));
        long retryDelayMs = Math.max(0, Math.min(properties.getTaskRetryDelayMs(), 30_000));
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            jdbc.update("UPDATE worker_task_runs SET attempt_count=?,updated_at=now() WHERE task_name=? AND run_key=?",
                attempt, taskName, runKey);
            try {
                Object result = task.get();
                String json = objectMapper.writeValueAsString(result);
                jdbc.update("""
                    UPDATE worker_task_runs SET status='SUCCEEDED',finished_at=now(),last_error=null,result=?::jsonb
                    WHERE task_name=? AND run_key=?
                    """, json, taskName, runKey);
                System.out.println(taskName + " succeeded: " + result);
                return;
            } catch (Exception error) {
                String message = safeMessage(error);
                if (attempt == maxAttempts) {
                    jdbc.update("""
                        UPDATE worker_task_runs SET status='FAILED',finished_at=now(),last_error=?,result=null
                        WHERE task_name=? AND run_key=?
                        """, message, taskName, runKey);
                    System.err.println(taskName + " failed after " + attempt + " attempt(s): " + message);
                    return;
                }
                try {
                    Thread.sleep(retryDelayMs * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    jdbc.update("""
                        UPDATE worker_task_runs SET status='FAILED',finished_at=now(),last_error=?,result=null
                        WHERE task_name=? AND run_key=?
                        """, "interrupted", taskName, runKey);
                    return;
                }
            }
        }
    }

    private String safeMessage(Exception error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage());
        return message.length() <= 1_000 ? message : message.substring(0, 1_000);
    }
}
