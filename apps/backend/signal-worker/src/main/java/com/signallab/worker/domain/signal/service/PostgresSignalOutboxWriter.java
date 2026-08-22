package com.signallab.worker.domain.signal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Atomic persistence boundary for a completed BUY-condition evaluation.
 * The unique signal tuple and outbox dedupe key make retries safe.
 */
@Service
public class PostgresSignalOutboxWriter {

    private final ObjectMapper objectMapper;

    public PostgresSignalOutboxWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PersistResult persist(Connection connection, BuySignal input) throws Exception {
        validate(input);
        boolean previousAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            assertStrategyOwnership(connection, input);
            InsertResult signal = insertOrFindSignal(connection, input);
            InsertResult outbox = insertOrVerifyOutbox(connection, input, signal.id());
            connection.commit();
            return new PersistResult(signal.id(), signal.created(), outbox.created());
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void assertStrategyOwnership(Connection connection, BuySignal input) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM public.strategy_versions
             WHERE id = CAST(? AS uuid) AND user_id = CAST(? AS uuid) AND finalized_at IS NOT NULL AND timeframe = 'D1'
            """)) {
            statement.setObject(1, input.strategyVersionId());
            statement.setObject(2, input.userId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Strategy version ownership or finalization mismatch");
            }
        }
    }

    private InsertResult insertOrFindSignal(Connection connection, BuySignal input) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO public.signals (
              user_id, strategy_version_id, instrument_id, timeframe, candle_close_at, signal_type,
              signal_strength, prior_liquidity_score, evidence, dataset_version, engine_version, data_is_stale
            ) VALUES (?, ?, ?, 'D1', ?, 'BUY_CONDITION', ?, ?, CAST(? AS jsonb), ?, ?, false)
            ON CONFLICT (strategy_version_id, instrument_id, timeframe, candle_close_at, signal_type) DO NOTHING
            RETURNING id
            """)) {
            statement.setObject(1, input.userId());
            statement.setObject(2, input.strategyVersionId());
            statement.setObject(3, input.instrumentId());
            statement.setObject(4, input.candleCloseAt());
            statement.setBigDecimal(5, input.signalStrength());
            statement.setBigDecimal(6, input.priorLiquidityScore());
            statement.setString(7, input.evidenceJson());
            statement.setString(8, input.datasetVersion());
            statement.setString(9, input.engineVersion());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return new InsertResult(UUID.fromString(rows.getString(1)), true);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, user_id FROM public.signals
             WHERE strategy_version_id = ? AND instrument_id = ? AND timeframe = 'D1'
               AND candle_close_at = ? AND signal_type = 'BUY_CONDITION'
            """)) {
            statement.setObject(1, input.strategyVersionId());
            statement.setObject(2, input.instrumentId());
            statement.setObject(3, input.candleCloseAt());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !input.userId().equals(rows.getObject("user_id", UUID.class))) {
                    throw new IllegalStateException("Signal dedupe conflict ownership mismatch");
                }
                return new InsertResult(rows.getObject("id", UUID.class), false);
            }
        }
    }

    private InsertResult insertOrVerifyOutbox(Connection connection, BuySignal input, UUID signalId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO public.push_outbox (user_id, signal_id, dedupe_key, status, redacted_payload)
            VALUES (?, ?, ?, 'PENDING', CAST(? AS jsonb))
            ON CONFLICT (dedupe_key) DO NOTHING RETURNING id
            """)) {
            statement.setObject(1, input.userId());
            statement.setObject(2, signalId);
            statement.setString(3, input.outboxDedupeKey());
            statement.setString(4, input.redactedPayloadJson());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return new InsertResult(UUID.fromString(rows.getString(1)), true);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT user_id, signal_id, redacted_payload::text FROM public.push_outbox WHERE dedupe_key = ?
            """)) {
            statement.setString(1, input.outboxDedupeKey());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()
                    || !input.userId().equals(rows.getObject("user_id", UUID.class))
                    || !signalId.equals(rows.getObject("signal_id", UUID.class))
                    || !sameJsonObject(rows.getString("redacted_payload"), input.redactedPayloadJson())) {
                    throw new IllegalStateException("Outbox dedupe conflict mismatch");
                }
                return new InsertResult(null, false);
            }
        }
    }

    private void validate(BuySignal input) {
        if (input == null || input.userId() == null || input.strategyVersionId() == null || input.instrumentId() == null
            || input.candleCloseAt() == null || blank(input.datasetVersion()) || blank(input.engineVersion())
            || blank(input.outboxDedupeKey()) || !jsonObject(input.evidenceJson()) || !jsonObject(input.redactedPayloadJson())) {
            throw new IllegalArgumentException("BUY signal persistence input is invalid");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private boolean jsonObject(String value) {
        if (value == null || value.length() > 20_000) return false;
        try {
            JsonNode node = objectMapper.readTree(value);
            return node != null && node.isObject();
        } catch (Exception error) {
            return false;
        }
    }
    private boolean sameJsonObject(String left, String right) {
        try {
            return objectMapper.readTree(left).equals(objectMapper.readTree(right));
        } catch (Exception error) {
            return false;
        }
    }

    private record InsertResult(UUID id, boolean created) {}
    public record PersistResult(UUID signalId, boolean signalCreated, boolean outboxCreated) {}
    public record BuySignal(
        UUID userId, UUID strategyVersionId, UUID instrumentId, OffsetDateTime candleCloseAt,
        java.math.BigDecimal signalStrength, java.math.BigDecimal priorLiquidityScore,
        String evidenceJson, String datasetVersion, String engineVersion,
        String outboxDedupeKey, String redactedPayloadJson
    ) {}
}
