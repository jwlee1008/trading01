package com.signallab.api.domain.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.api.domain.order.dto.SellRuleRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SellRuleService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SellRuleService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SavedRule save(UUID userId, UUID positionId, SellRuleRequest request) {
        NormalizedRule input = normalize(request);
        PositionRow position = findPosition(userId, positionId);
        if (position == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "포지션을 찾을 수 없습니다.");
        if ("CLOSED".equals(position.status()) || "ARCHIVED".equals(position.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "종료 포지션에는 규칙을 적용할 수 없습니다.");
        }
        if ("RANKED_PAPER".equals(position.portfolioKind())) {
            if (input.manualOnly()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공식 랭킹은 자동 매도 규칙이 하나 이상 필요합니다.");
            }
            if (position.sellRuleVersionId() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "공식 랭킹 규칙은 트랙 시작 뒤 바꿀 수 없습니다.");
            }
        }

        UUID setId = jdbcTemplate.queryForObject(
            """
            insert into sell_rule_sets (user_id, name)
            values (?, ?)
            on conflict (user_id, name) do update set updated_at = now()
            returning id
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class), userId, "position:" + positionId
        );
        Integer latestVersion = jdbcTemplate.query(
            "select version from sell_rule_versions where sell_rule_set_id = ? order by version desc limit 1 for update",
            (rs, rowNum) -> rs.getInt("version"), setId
        ).stream().findFirst().orElse(0);
        int version = latestVersion + 1;
        UUID versionId = jdbcTemplate.queryForObject(
            """
            insert into sell_rule_versions
              (user_id, sell_rule_set_id, version, stop_loss_rate, take_profit_rate, trailing_stop_rate,
               max_holding_sessions, technical_logic, technical_conditions, manual_only)
            values (?, ?, ?, ?, ?, ?, ?, cast(? as rule_logic), cast(? as jsonb), ?)
            returning id
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            userId, setId, version, input.stopLossRate(), input.takeProfitRate(), input.trailingStopRate(),
            input.maxHoldingSessions(), input.technicalLogic(), technicalRulesJson(input.technicalRules()), input.manualOnly()
        );
        jdbcTemplate.update(
            "update position_sell_rule_bindings set effective_to = now() where position_id = ? and effective_to is null",
            positionId
        );
        jdbcTemplate.update(
            """
            insert into position_sell_rule_bindings (user_id, position_id, sell_rule_version_id, effective_from)
            values (?, ?, ?, now())
            """,
            userId, positionId, versionId
        );
        jdbcTemplate.update("update positions set sell_rule_version_id = ?, updated_at = now() where id = ?", versionId, positionId);
        return new SavedRule(versionId, version, input.toContractInput(), Instant.now().toString());
    }

    private PositionRow findPosition(UUID userId, UUID positionId) {
        return jdbcTemplate.query(
            """
            select p.status, p.sell_rule_version_id, pf.kind
            from positions p join portfolios pf on pf.id = p.portfolio_id
            where p.id = ? and p.user_id = ?
            """,
            rs -> rs.next() ? new PositionRow(rs.getString("status"), rs.getObject("sell_rule_version_id", UUID.class), rs.getString("kind")) : null,
            positionId, userId
        );
    }

    private NormalizedRule normalize(SellRuleRequest request) {
        if (request == null) throw badRequest("매도 규칙 본문이 필요합니다.");
        List<com.fasterxml.jackson.databind.JsonNode> rules = request.technicalRules() == null ? List.of() : request.technicalRules();
        if (rules.size() > 3) throw badRequest("기술 조건은 최대 3개입니다.");
        String logic = request.technicalLogic() == null ? "ANY" : request.technicalLogic();
        if (!"ANY".equals(logic) && !"ALL".equals(logic)) throw badRequest("technicalLogic은 ANY 또는 ALL이어야 합니다.");
        BigDecimal stopLoss = percentRate(request.stopLossPct(), "stopLossPct", true);
        BigDecimal takeProfit = percentRate(request.takeProfitPct(), "takeProfitPct", false);
        BigDecimal trailingStop = percentRate(request.trailingStopPct(), "trailingStopPct", true);
        if (request.maxHoldingSessions() != null && request.maxHoldingSessions() <= 0) throw badRequest("maxHoldingSessions는 양수여야 합니다.");
        boolean automatic = stopLoss != null || takeProfit != null || trailingStop != null || request.maxHoldingSessions() != null || !rules.isEmpty();
        boolean manualOnly = Boolean.TRUE.equals(request.manualOnly());
        if (manualOnly == automatic) throw badRequest("수동 관리 또는 자동 규칙 중 하나를 선택하세요.");
        return new NormalizedRule(stopLoss, takeProfit, trailingStop, request.maxHoldingSessions(), rules.isEmpty() ? null : logic, rules, manualOnly);
    }

    private BigDecimal percentRate(BigDecimal value, String field, boolean maximumOneHundred) {
        if (value == null) return null;
        if (value.signum() <= 0 || (maximumOneHundred && value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw badRequest(field + " 값이 범위를 벗어났습니다.");
        }
        return value.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
    }

    private String technicalRulesJson(List<com.fasterxml.jackson.databind.JsonNode> rules) {
        try { return objectMapper.writeValueAsString(rules); }
        catch (JsonProcessingException exception) { throw badRequest("technicalRules 형식이 올바르지 않습니다."); }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record SavedRule(UUID id, int version, ContractInput input, String createdAt) {}
    public record ContractInput(BigDecimal stopLossPct, BigDecimal takeProfitPct, BigDecimal trailingStopPct,
                                Integer maxHoldingSessions, String technicalLogic, List<com.fasterxml.jackson.databind.JsonNode> technicalRules,
                                boolean manualOnly) {}
    public record NormalizedRule(BigDecimal stopLossRate, BigDecimal takeProfitRate, BigDecimal trailingStopRate,
                                 Integer maxHoldingSessions, String technicalLogic, List<com.fasterxml.jackson.databind.JsonNode> technicalRules,
                                 boolean manualOnly) {
        ContractInput toContractInput() {
            return new ContractInput(
                asPercent(stopLossRate), asPercent(takeProfitRate), asPercent(trailingStopRate),
                maxHoldingSessions, technicalLogic == null ? "ANY" : technicalLogic, technicalRules, manualOnly
            );
        }

        private static BigDecimal asPercent(BigDecimal rate) {
            return rate == null ? null : rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
        }
    }
    private record PositionRow(String status, UUID sellRuleVersionId, String portfolioKind) {}
}
