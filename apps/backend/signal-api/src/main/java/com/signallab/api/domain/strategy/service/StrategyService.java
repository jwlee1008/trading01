package com.signallab.api.domain.strategy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.signallab.api.domain.strategy.dto.StrategyRequest;
import com.signallab.api.domain.strategy.dto.StrategyVersionResponse;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StrategyService {

    private static final Set<String> OPERATORS = Set.of("GT", "GTE", "LT", "LTE", "EQ", "CROSSES_ABOVE", "CROSSES_BELOW");
    private static final Set<String> INDICATORS = Set.of("SMA", "EMA", "RSI", "MACD", "BOLLINGER", "VOLUME_SPIKE", "STOCHASTIC", "ATR", "ADX", "OBV");
    private static final String ENGINE_VERSION = "mvp-2026-08-15";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StrategyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<StrategyVersionResponse> findByUserId(UUID userId) {
        String versionsSql = """
            SELECT sv.id, sv.user_id, sv.strategy_id, sv.version, s.name, s.is_public,
                   sv.universe_version_id::text AS universe_version_id, ud.kind::text AS universe_kind,
                   sv.root_logic, sv.notifications_enabled, sv.created_at, false AS locked
            FROM strategy_versions sv
            JOIN strategies s ON s.id = sv.strategy_id
            JOIN universe_versions uv ON uv.id = sv.universe_version_id
            JOIN universe_definitions ud ON ud.id = uv.universe_definition_id
            WHERE sv.user_id = ? AND s.archived_at IS NULL
            ORDER BY sv.created_at ASC
            """;
        return jdbcTemplate.query(versionsSql, (rs, rowNum) -> {
            UUID versionId = UUID.fromString(rs.getString("id"));
            return new VersionRow(
                versionId,
                UUID.fromString(rs.getString("user_id")),
                UUID.fromString(rs.getString("strategy_id")),
                rs.getInt("version"),
                rs.getString("name"),
                rs.getString("universe_version_id"),
                rs.getString("universe_kind"),
                "ALL".equals(rs.getString("root_logic")) ? "AND" : "OR",
                rs.getBoolean("notifications_enabled"),
                rs.getBoolean("is_public"),
                rs.getBoolean("locked"),
                rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC)
            );
        }, userId).stream().map(row -> new StrategyVersionResponse(
            row.id(), row.userId(), row.strategyId(), row.version(), row.name(), row.universeVersionId(), row.universeKind(), row.logic(),
            rulesFor(row.id()), row.alertsEnabled(), row.isPublic(), row.locked(), row.createdAt()
        )).toList();
    }

    @Transactional
    public StrategyVersionResponse create(UUID userId, StrategyRequest request) {
        ValidatedRequest input = validate(request);
        UUID universeVersionId = resolveFinalizedUniverseVersion(input.universeVersionId());
        try {
            UUID strategyId = jdbcTemplate.queryForObject(
                "INSERT INTO strategies (user_id, name, is_public) VALUES (?, ?, ?) RETURNING id",
                UUID.class, userId, input.name(), input.isPublic()
            );
            return createVersion(userId, strategyId, 1, universeVersionId, input);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 전략이 이미 있습니다.");
        }
    }

    @Transactional
    public StrategyVersionResponse revise(UUID userId, UUID strategyId, StrategyRequest request) {
        ValidatedRequest input = validate(request);
        Integer owned = jdbcTemplate.query(
            "SELECT 1 FROM strategies WHERE id = ? AND user_id = ? AND archived_at IS NULL",
            rs -> rs.next() ? rs.getInt(1) : null, strategyId, userId
        );
        if (owned == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "전략을 찾을 수 없습니다.");
        }
        UUID universeVersionId = resolveFinalizedUniverseVersion(input.universeVersionId());
        Integer latest = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version), 0) FROM strategy_versions WHERE strategy_id = ? AND user_id = ?",
            Integer.class, strategyId, userId
        );
        try {
            jdbcTemplate.update("UPDATE strategies SET name = ?, is_public = ?, updated_at = NOW() WHERE id = ?", input.name(), input.isPublic(), strategyId);
            jdbcTemplate.queryForObject("SELECT set_config('app.strategy_purge','on',true)", String.class);
            deleteSignalsForStrategy(userId, strategyId);
            return createVersion(userId, strategyId, (latest == null ? 0 : latest) + 1, universeVersionId, input);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 전략이 이미 있습니다.");
        }
    }

    @Transactional
    public void delete(UUID userId, UUID strategyId) {
        Integer owned = jdbcTemplate.query(
            "SELECT 1 FROM strategies WHERE id = ? AND user_id = ?",
            rs -> rs.next() ? rs.getInt(1) : null, strategyId, userId
        );
        if (owned == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "전략을 찾을 수 없습니다.");

        // Manual trade records remain, but links to the strategy and its signals are removed.
        jdbcTemplate.queryForObject("SELECT set_config('app.account_purge','on',true)", String.class);
        jdbcTemplate.queryForObject("SELECT set_config('app.strategy_purge','on',true)", String.class);
        deleteSignalsForStrategy(userId, strategyId);
        jdbcTemplate.update("""
            UPDATE positions SET strategy_version_id = NULL, updated_at = NOW()
            WHERE user_id = ? AND strategy_version_id IN (
              SELECT id FROM strategy_versions WHERE strategy_id = ?
            )
            """, userId, strategyId);
        jdbcTemplate.update("""
            UPDATE position_executions SET strategy_version_id = NULL
            WHERE user_id = ? AND strategy_version_id IN (
              SELECT id FROM strategy_versions WHERE strategy_id = ?
            )
            """, userId, strategyId);
        jdbcTemplate.update("""
            DELETE FROM backtest_runs
            WHERE user_id = ? AND strategy_version_id IN (
              SELECT id FROM strategy_versions WHERE strategy_id = ?
            )
            """, userId, strategyId);
        jdbcTemplate.update("DELETE FROM strategies WHERE id = ? AND user_id = ?", strategyId, userId);
    }

    private void deleteSignalsForStrategy(UUID userId, UUID strategyId) {
        jdbcTemplate.update("""
            DELETE FROM push_outbox
            WHERE signal_id IN (
              SELECT sig.id FROM signals sig
              JOIN strategy_versions sv ON sv.id = sig.strategy_version_id
              WHERE sv.strategy_id = ? AND sig.user_id = ?
            )
            """, strategyId, userId);
        jdbcTemplate.update("""
            UPDATE positions SET buy_signal_id = NULL, updated_at = NOW()
            WHERE user_id = ? AND buy_signal_id IN (
              SELECT sig.id FROM signals sig
              JOIN strategy_versions sv ON sv.id = sig.strategy_version_id
              WHERE sv.strategy_id = ?
            )
            """, userId, strategyId);
        jdbcTemplate.update("""
            UPDATE position_executions SET source_signal_id = NULL
            WHERE user_id = ? AND source_signal_id IN (
              SELECT sig.id FROM signals sig
              JOIN strategy_versions sv ON sv.id = sig.strategy_version_id
              WHERE sv.strategy_id = ?
            )
            """, userId, strategyId);
        jdbcTemplate.update("""
            DELETE FROM signals
            WHERE user_id = ? AND strategy_version_id IN (
              SELECT id FROM strategy_versions WHERE strategy_id = ?
            )
            """, userId, strategyId);
    }

    private StrategyVersionResponse createVersion(UUID userId, UUID strategyId, int version, UUID universeVersionId, ValidatedRequest input) {
        VersionInsert inserted = jdbcTemplate.queryForObject(
            """
            INSERT INTO strategy_versions (user_id, strategy_id, version, universe_version_id, root_logic, notifications_enabled, engine_version)
            VALUES (?, ?, ?, ?, ?::rule_logic, ?, ?) RETURNING id, created_at
            """,
            (rs, rowNum) -> new VersionInsert(UUID.fromString(rs.getString("id")), rs.getTimestamp("created_at")),
            userId, strategyId, version, universeVersionId, "AND".equals(input.logic()) ? "ALL" : "ANY", input.alertsEnabled(), ENGINE_VERSION
        );
        for (int index = 0; index < input.rules().size(); index++) {
            JsonNode rule = input.rules().get(index);
            String indicatorCode = firstIndicator(rule);
            UUID definitionId = indicatorDefinitionId(indicatorCode);
            ObjectNode params = objectMapper.createObjectNode();
            params.set("sourceRule", rule);
            JsonNode right = rule.path("right");
            BigDecimal compareValue = right.path("kind").asText().equals("VALUE") && right.path("value").isNumber()
                ? right.get("value").decimalValue()
                : null;
            jdbcTemplate.update(
                """
                INSERT INTO strategy_rules (user_id, strategy_version_id, rule_index, indicator_definition_id, operator, params, compare_value)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                userId, inserted.id(), index + 1, definitionId, rule.path("operator").asText(), params.toString(), compareValue
            );
        }
        jdbcTemplate.update("UPDATE strategy_versions SET finalized_at = NOW() WHERE id = ?", inserted.id());
        String universeKind = jdbcTemplate.queryForObject("""
            SELECT ud.kind::text FROM universe_versions uv
            JOIN universe_definitions ud ON ud.id=uv.universe_definition_id WHERE uv.id=?
            """, String.class, universeVersionId);
        return new StrategyVersionResponse(
            inserted.id(), userId, strategyId, version, input.name(), universeVersionId.toString(), universeKind, input.logic(), input.rules(),
            input.alertsEnabled(), input.isPublic(), false, inserted.createdAt().toInstant().atOffset(ZoneOffset.UTC)
        );
    }

    private List<JsonNode> rulesFor(UUID strategyVersionId) {
        return jdbcTemplate.query(
            "SELECT params FROM strategy_rules WHERE strategy_version_id = ? ORDER BY rule_index ASC",
            (rs, rowNum) -> parseStoredRule(rs.getString("params")), strategyVersionId
        ).stream().filter(rule -> rule != null).toList();
    }

    private JsonNode parseStoredRule(String params) {
        try {
            JsonNode root = objectMapper.readTree(params);
            return root.get("sourceRule");
        } catch (Exception exception) {
            throw new IllegalStateException("저장된 전략 규칙을 읽을 수 없습니다.", exception);
        }
    }

    private UUID resolveFinalizedUniverseVersion(String value) {
        List<UUID> rows = jdbcTemplate.query(
            """
            SELECT id FROM universe_versions
            WHERE finalized_at IS NOT NULL AND (id::text = ? OR source_revision = ?)
            ORDER BY created_at DESC LIMIT 1
            """, (rs, rowNum) -> UUID.fromString(rs.getString("id")), value, value
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 종목군 버전입니다.");
        }
        return rows.getFirst();
    }

    private UUID indicatorDefinitionId(String code) {
        List<UUID> rows = jdbcTemplate.query(
            "SELECT id FROM indicator_definitions WHERE code = ? AND is_active = true ORDER BY version DESC LIMIT 1",
            (rs, rowNum) -> UUID.fromString(rs.getString("id")), code
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 지표입니다: " + code);
        }
        return rows.getFirst();
    }

    private ValidatedRequest validate(StrategyRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 40 || request.universeVersionId() == null || request.universeVersionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 입력이 올바르지 않습니다.");
        }
        if (!"AND".equals(request.logic()) && !"OR".equals(request.logic())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 논리값이 올바르지 않습니다.");
        }
        if (request.rules() == null || request.rules().isEmpty() || request.rules().size() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 규칙은 1~5개여야 합니다.");
        }
        List<JsonNode> canonicalRules = new ArrayList<>();
        Set<String> identities = new java.util.HashSet<>();
        for (JsonNode rule : request.rules()) {
            JsonNode canonical = canonicalRule(rule);
            if (!identities.add(canonical.toString())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 전략 규칙을 중복해서 사용할 수 없습니다.");
            canonicalRules.add(canonical);
        }
        return new ValidatedRequest(name, request.universeVersionId(), request.logic(), List.copyOf(canonicalRules),
            request.alertsEnabled() == null || request.alertsEnabled(), request.isPublic() != null && request.isPublic());
    }

    private JsonNode canonicalRule(JsonNode rule) {
        if (rule == null || !rule.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 규칙이 올바르지 않습니다.");
        if (rule.has("indicatorId")) {
            ObjectNode converted = objectMapper.createObjectNode();
            ObjectNode left = converted.putObject("left");
            left.put("kind", "INDICATOR");
            left.put("indicatorId", rule.path("indicatorId").asText());
            if (rule.has("outputKey")) left.put("outputKey", rule.path("outputKey").asText());
            left.set("params", rule.has("params") ? rule.get("params") : objectMapper.createObjectNode());
            converted.put("operator", rule.path("operator").asText());
            converted.putObject("right").put("kind", "VALUE").set("value", rule.get("value"));
            rule = converted;
        }
        if (!OPERATORS.contains(rule.path("operator").asText())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 전략 연산자입니다.");
        validateOperand(rule.get("left"), false);
        validateOperand(rule.get("right"), true);
        if (!"INDICATOR".equals(rule.path("left").path("kind").asText())
            && !"INDICATOR".equals(rule.path("right").path("kind").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "각 전략 규칙에는 지표가 하나 이상 필요합니다.");
        }
        return rule;
    }

    private void validateOperand(JsonNode operand, boolean allowValue) {
        if (operand == null || !operand.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 피연산자가 올바르지 않습니다.");
        String kind = operand.path("kind").asText();
        if ("CLOSE".equals(kind)) return;
        if (allowValue && "VALUE".equals(kind) && operand.path("value").isNumber()) return;
        if ("INDICATOR".equals(kind) && INDICATORS.contains(operand.path("indicatorId").asText()) && operand.path("params").isObject()) return;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 피연산자가 올바르지 않습니다.");
    }

    private String firstIndicator(JsonNode rule) {
        if ("INDICATOR".equals(rule.path("left").path("kind").asText())) return rule.path("left").path("indicatorId").asText();
        if ("INDICATOR".equals(rule.path("right").path("kind").asText())) return rule.path("right").path("indicatorId").asText();
        return "SMA";
    }

    private record ValidatedRequest(String name, String universeVersionId, String logic, List<JsonNode> rules, boolean alertsEnabled, boolean isPublic) {}
    private record VersionInsert(UUID id, Timestamp createdAt) {}
    private record VersionRow(UUID id, UUID userId, UUID strategyId, int version, String name, String universeVersionId, String universeKind, String logic, boolean alertsEnabled, boolean isPublic, boolean locked, java.time.OffsetDateTime createdAt) {}
}
