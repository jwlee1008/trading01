package com.signallab.api.domain.signal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.api.domain.signal.dto.SignalResponse;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SignalService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SignalService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<SignalResponse> findByUserId(UUID userId, String type) {
        if (type != null && !type.isBlank() && !"BUY_CONDITION".equals(type)) {
            return List.of();
        }
        List<SignalRow> rows = jdbcTemplate.query(
            """
            SELECT s.id, s.user_id, s.strategy_version_id, i.symbol, i.name_ko, s.candle_close_at,
                   s.signal_type, s.evidence, s.data_is_stale, c.close AS candle_close_price
            FROM signals s
            JOIN strategy_versions sv ON sv.id = s.strategy_version_id
            JOIN strategies strategy ON strategy.id = sv.strategy_id
            JOIN instruments i ON i.id = s.instrument_id
            LEFT JOIN candles c ON c.instrument_id = s.instrument_id AND c.timeframe = s.timeframe
                               AND c.close_at = s.candle_close_at
            WHERE s.user_id = ? AND strategy.archived_at IS NULL
              AND NOT EXISTS (
                SELECT 1 FROM strategy_versions newer
                WHERE newer.strategy_id = sv.strategy_id
                  AND newer.finalized_at IS NOT NULL
                  AND newer.version > sv.version
              )
            ORDER BY s.candle_close_at DESC
            """,
            (rs, rowNum) -> new SignalRow(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("user_id")),
                UUID.fromString(rs.getString("strategy_version_id")), rs.getString("symbol"), rs.getString("name_ko"),
                rs.getTimestamp("candle_close_at").toInstant().atOffset(ZoneOffset.UTC), rs.getString("signal_type"),
                rs.getString("evidence"), rs.getBoolean("data_is_stale"), rs.getString("candle_close_price")
            ), userId
        );
        Set<UUID> acknowledged = acknowledgedSignalIds(userId, rows.stream().map(SignalRow::id).toList());
        return rows.stream().map(row -> toResponse(row, acknowledged.contains(row.id()))).toList();
    }

    public SignalResponse findById(UUID userId, UUID signalId) {
        return findByUserId(userId, null).stream()
            .filter(signal -> signal.id().equals(signalId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "신호를 찾을 수 없습니다."));
    }

    @Transactional
    public SignalResponse acknowledge(UUID userId, UUID signalId) {
        SignalResponse signal = findById(userId, signalId);
        if ("ACKNOWLEDGED".equals(signal.status())) {
            return signal;
        }
        jdbcTemplate.update(
            """
            INSERT INTO audit_logs (user_id, actor_user_id, action, entity_type, entity_id, after_redacted)
            VALUES (?, ?, 'SIGNAL_ACKNOWLEDGED', 'signal', ?, '{"status":"ACKNOWLEDGED"}'::jsonb)
            """, userId, userId, signalId
        );
        return new SignalResponse(
            signal.id(), signal.userId(), signal.strategyVersionId(), signal.symbol(), signal.name(), signal.type(),
            signal.candleClose(), signal.closePrice(), "ACKNOWLEDGED", signal.reasons(), signal.stale(), signal.userActionRequired()
        );
    }

    private Set<UUID> acknowledgedSignalIds(UUID userId, List<UUID> signalIds) {
        if (signalIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(signalIds.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        parameters.addAll(signalIds);
        return new HashSet<>(jdbcTemplate.query(
            "SELECT entity_id FROM audit_logs WHERE user_id = ? AND action = 'SIGNAL_ACKNOWLEDGED' AND entity_id IN (" + placeholders + ")",
            (rs, rowNum) -> UUID.fromString(rs.getString("entity_id")), parameters.toArray()
        ));
    }

    private SignalResponse toResponse(SignalRow row, boolean acknowledged) {
        JsonNode evidence;
        try {
            evidence = objectMapper.readTree(row.evidence());
        } catch (Exception exception) {
            throw new IllegalStateException("저장된 신호 근거를 읽을 수 없습니다.", exception);
        }
        List<SignalResponse.Reason> reasons = new ArrayList<>();
        for (JsonNode reason : evidence.path("reasons")) {
            reasons.add(new SignalResponse.Reason(reason.path("label").asText(), reason.path("value").asText()));
        }
        if (reasons.isEmpty()) {
            for (int index = 0; evidence.has("rule." + index + ".matched"); index++) {
                String prefix = "rule." + index;
                if (!evidence.path(prefix + ".matched").asBoolean()) continue;
                String left = evidence.has(prefix + ".left") ? evidence.get(prefix + ".left").asText() : "-";
                String right = evidence.has(prefix + ".right") ? evidence.get(prefix + ".right").asText() : "-";
                reasons.add(new SignalResponse.Reason("전략 규칙 " + (index + 1) + " 충족", left + " / 기준 " + right));
            }
        }
        JsonNode close = evidence.has("closePrice") ? evidence.get("closePrice") : evidence.get("close");
        String closePrice = close != null && (close.isNumber() || close.isTextual()) ? close.asText()
            : row.candleClosePrice() == null ? "0" : row.candleClosePrice();
        return new SignalResponse(
            row.id(), row.userId(), row.strategyVersionId(), row.symbol(), row.name(),
            "SELL_CONDITION".equals(row.type()) ? "SELL_CONDITION" : "BUY_CONDITION", row.candleClose(), closePrice,
            acknowledged ? "ACKNOWLEDGED" : "ACTIVE", List.copyOf(reasons), row.stale(), true
        );
    }

    private record SignalRow(
        UUID id, UUID userId, UUID strategyVersionId, String symbol, String name,
        java.time.OffsetDateTime candleClose, String type, String evidence, boolean stale, String candleClosePrice
    ) {}
}
