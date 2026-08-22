package com.signallab.worker.domain.outbox.service;

import com.signallab.worker.global.config.WorkerProperties;
import com.signallab.worker.global.config.RuntimeEnvironment;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Dispatches redacted push messages using short database leases. */
@Service
public class PostgresOutboxDispatcher {

    public DispatchReport dispatch(WorkerProperties properties) {
        if (!properties.isEnabled() || !"console".equalsIgnoreCase(properties.getPushProvider())) {
            return new DispatchReport(0, 0, "disabled");
        }
        String databaseUrl = RuntimeEnvironment.get("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is required when Spring Worker console push is enabled");
        }
        int batchSize = bounded(properties.getOutboxBatchSize(), 50, 1, 500, "outboxBatchSize");
        int leaseSeconds = bounded(properties.getOutboxLeaseSeconds(), 300, 1, 3600, "outboxLeaseSeconds");
        try (Connection connection = DriverManager.getConnection(jdbcUrl(databaseUrl))) {
            connection.setAutoCommit(false);
            List<OutboxMessage> messages = claim(connection, batchSize, leaseSeconds);
            int sent = 0;
            int failed = 0;
            for (OutboxMessage message : messages) {
                try {
                    // The payload is intentionally redacted before it reaches this boundary.
                    System.out.printf("{\"event\":\"push.console\",\"outboxId\":\"%s\",\"payload\":%s}%n", message.id(), message.payload());
                    markSent(connection, message.id());
                    sent++;
                } catch (RuntimeException error) {
                    markFailed(connection, message.id(), error.getClass().getSimpleName());
                    failed++;
                }
            }
            connection.commit();
            return new DispatchReport(sent, failed, "console");
        } catch (Exception error) {
            throw new IllegalStateException("Spring Worker outbox dispatch failed", error);
        }
    }

    private List<OutboxMessage> claim(Connection connection, int batchSize, int leaseSeconds) throws Exception {
        String sql = """
            WITH claimed AS (
              SELECT id FROM public.push_outbox
              WHERE (status IN ('PENDING', 'FAILED') AND available_at <= now())
                 OR (status = 'PROCESSING' AND updated_at < now() - (? * interval '1 second'))
              ORDER BY available_at, id
              FOR UPDATE SKIP LOCKED
              LIMIT ?
            )
            UPDATE public.push_outbox o
               SET status = 'PROCESSING', attempt_count = o.attempt_count + 1,
                   last_error_code = NULL, updated_at = now()
              FROM claimed
             WHERE o.id = claimed.id
            RETURNING o.id, o.redacted_payload::text
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, leaseSeconds);
            statement.setInt(2, batchSize);
            try (ResultSet rows = statement.executeQuery()) {
                List<OutboxMessage> result = new ArrayList<>();
                while (rows.next()) result.add(new OutboxMessage(rows.getString(1), rows.getString(2)));
                return result;
            }
        }
    }

    private void markSent(Connection connection, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE public.push_outbox SET status = 'SENT', sent_at = now(), updated_at = now()
             WHERE id = CAST(? AS uuid) AND status = 'PROCESSING'
            """)) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void markFailed(Connection connection, String id, String errorCode) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE public.push_outbox SET status = 'FAILED', last_error_code = ?,
                available_at = now() + interval '1 minute', updated_at = now()
             WHERE id = CAST(? AS uuid) AND status = 'PROCESSING'
            """)) {
            statement.setString(1, errorCode.substring(0, Math.min(errorCode.length(), 100)));
            statement.setString(2, id);
            statement.executeUpdate();
        }
    }

    private static int bounded(int value, int fallback, int minimum, int maximum, String name) {
        int resolved = value == 0 ? fallback : value;
        if (resolved < minimum || resolved > maximum) throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        return resolved;
    }

    private static String jdbcUrl(String url) {
        return url.startsWith("postgresql://") ? "jdbc:" + url : url;
    }

    private record OutboxMessage(String id, String payload) {}
    public record DispatchReport(int sent, int failed, String provider) {}
}
