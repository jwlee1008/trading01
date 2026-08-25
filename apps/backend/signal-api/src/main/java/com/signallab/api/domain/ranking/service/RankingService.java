package com.signallab.api.domain.ranking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.api.domain.strategy.dto.StrategyRequest;
import com.signallab.api.domain.strategy.service.StrategyService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RankingService {
    private static final int MINIMUM_RECORDS = 1;
    private static final Map<String, String> PERIODS = Map.of("3M", "M3", "6M", "M6", "1Y", "Y1");
    private final StrategyService strategyService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RankingService(StrategyService strategyService, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.strategyService = strategyService;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Object copyPublicStrategy(UUID userId, UUID publicProfileId, UUID strategyVersionId) {
        JsonNode row = jdbc.query("""
            SELECT jsonb_build_object(
              'name',psv.strategy_name,'universeVersionId',psv.universe_version_id::text,
              'logic',case when psv.root_logic='ALL' then 'AND' else 'OR' end,
              'rules',coalesce(jsonb_agg(psr.params->'sourceRule' order by psr.rule_index)
                filter (where psr.rule_index is not null),'[]'::jsonb)
            )
            FROM public_strategy_versions psv
            LEFT JOIN public_strategy_rules psr
              ON psr.public_profile_id=psv.public_profile_id
             AND psr.strategy_version_id=psv.strategy_version_id
            WHERE psv.public_profile_id=? AND psv.strategy_version_id=?
            GROUP BY psv.strategy_name,psv.universe_version_id,psv.root_logic
            """, rs -> rs.next() ? parse(rs.getString(1)) : null, publicProfileId, strategyVersionId);
        if (row == null || !row.path("rules").isArray() || row.path("rules").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "복사할 공개 전략을 찾을 수 없습니다.");
        }
        List<JsonNode> rules = new ArrayList<>();
        row.path("rules").forEach(rules::add);
        return strategyService.create(userId, new StrategyRequest(
            requiredText(row, "name") + " 복사본", requiredText(row, "universeVersionId"),
            row.path("logic").asText("AND"), rules, true, false
        ));
    }

    public Map<String, Object> rankings(String period) {
        String requested = period == null || period.isBlank() ? "3M" : period.toUpperCase();
        String dbPeriod = PERIODS.get(requested);
        if (dbPeriod == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 랭킹 기간입니다.");

        int months = switch (requested) { case "3M" -> 3; case "6M" -> 6; default -> 12; };
        List<Map<String, Object>> users = jdbc.query("""
            WITH manual_activity AS (
              SELECT e.user_id,count(*)::int record_count,max(e.executed_at) latest_at
              FROM position_executions e
              WHERE e.portfolio_kind='MANUAL_LIVE' AND e.event_type='EXECUTION'
                AND e.reverses_execution_id IS NULL
                AND e.executed_at>=now()-make_interval(months => ?)
              GROUP BY e.user_id
            ), manual_sales AS (
              SELECT e.user_id,count(*)::int sell_count,
                     sum((e.unit_price-pos.average_cost)*e.quantity-e.fee-e.tax) realized_pnl,
                     sum(pos.average_cost*e.quantity+e.fee) invested_amount
              FROM position_executions e
              JOIN positions pos ON pos.id=e.position_id AND pos.portfolio_kind='MANUAL_LIVE'
              WHERE e.portfolio_kind='MANUAL_LIVE' AND e.side='SELL'
                AND e.event_type='EXECUTION' AND e.reverses_execution_id IS NULL
                AND e.executed_at>=now()-make_interval(months => ?)
              GROUP BY e.user_id
            ), eligible AS (
              SELECT pr.public_profile_id,pr.nickname,ma.record_count trade_count,ma.latest_at,
                     case when coalesce(ms.invested_amount,0)>0
                       then ms.realized_pnl/ms.invested_amount else 0 end period_return,
                     first_trade.started_at
              FROM manual_activity ma JOIN profiles pr ON pr.user_id=ma.user_id
              LEFT JOIN manual_sales ms ON ms.user_id=ma.user_id
              JOIN LATERAL (
                SELECT min(executed_at) started_at FROM position_executions
                WHERE user_id=ma.user_id AND portfolio_kind='MANUAL_LIVE'
              ) first_trade ON true
              WHERE pr.is_public AND pr.deleted_at IS NULL
            ), ranked AS (
              SELECT *,rank() over (ORDER BY period_return DESC,trade_count DESC,started_at,nickname) rank
              FROM eligible
            ) SELECT * FROM ranked ORDER BY rank LIMIT 100
            """, (rs, index) -> {
                Map<String, Object> returns = new LinkedHashMap<>();
                returns.put("3m", "3M".equals(requested) ? rs.getBigDecimal("period_return").movePointRight(2) : 0);
                returns.put("6m", "6M".equals(requested) ? rs.getBigDecimal("period_return").movePointRight(2) : 0);
                returns.put("1y", "1Y".equals(requested) ? rs.getBigDecimal("period_return").movePointRight(2) : 0);
                returns.put("all", rs.getBigDecimal("period_return").movePointRight(2));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("public_profile_id")); row.put("rank", rs.getInt("rank"));
                row.put("nickname", rs.getString("nickname")); row.put("returnRate", returns);
                row.put("universeId", "all"); row.put("mdd", 0);
                row.put("trades", rs.getInt("trade_count"));
                row.put("days", java.time.Duration.between(rs.getTimestamp("started_at").toInstant(), java.time.Instant.now()).toDays());
                row.put("strategyName", "사용자 작성 실제 매매"); row.put("public", true);
                row.put("asOf", rs.getTimestamp("latest_at").toInstant().toString());
                return row;
            }, months, months);

        for (Map<String, Object> user : users) {
            user.put("strategies", publicStrategies(UUID.fromString(user.get("id").toString())));
        }

        String asOf = jdbc.query("""
            SELECT max(executed_at) FROM position_executions
            WHERE portfolio_kind='MANUAL_LIVE' AND side='SELL' AND event_type='EXECUTION'
            """, rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant().toString() : null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("period", requested); response.put("asOf", asOf);
        response.put("periodStart", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusMonths(months).toString());
        response.put("minimumTrades", MINIMUM_RECORDS);
        response.put("users", users);
        response.put("disclosure", "테스트 기준: 공개 프로필이 실제 매매 기록을 한 번이라도 입력하면 표시합니다. 매도 전에는 실현수익률을 0%로 표시하며, 증권사 인증 내역이 아닙니다.");
        return response;
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (Exception error) { throw new IllegalStateException("랭킹 snapshot JSON을 읽을 수 없습니다.", error); }
    }

    private List<Map<String, Object>> publicStrategies(UUID publicProfileId) {
        return jdbc.query("""
            WITH latest AS (
              SELECT DISTINCT ON (strategy_id)
                public_profile_id,strategy_id,strategy_name,strategy_version_id,version,
                universe_version_id,root_logic,created_at
              FROM public_strategy_versions
              WHERE public_profile_id=?
              ORDER BY strategy_id,version DESC
            )
            SELECT l.strategy_id,l.strategy_name,l.strategy_version_id,l.version,
                   ud.kind::text universe_kind,l.root_logic,
                   coalesce(array_agg(psr.indicator_code order by psr.rule_index)
                     filter (where psr.rule_index is not null),array[]::text[]) indicator_codes
            FROM latest l
            JOIN universe_versions uv ON uv.id=l.universe_version_id
            JOIN universe_definitions ud ON ud.id=uv.universe_definition_id
            LEFT JOIN public_strategy_rules psr
              ON psr.public_profile_id=l.public_profile_id
             AND psr.strategy_version_id=l.strategy_version_id
            GROUP BY l.strategy_id,l.strategy_name,l.strategy_version_id,l.version,
                     ud.kind,l.root_logic,l.created_at
            ORDER BY l.created_at DESC
            LIMIT 20
            """, (rs, index) -> {
                Map<String, Object> strategy = new LinkedHashMap<>();
                strategy.put("id", rs.getString("strategy_version_id"));
                strategy.put("strategyId", rs.getString("strategy_id"));
                strategy.put("name", rs.getString("strategy_name"));
                strategy.put("version", rs.getInt("version"));
                strategy.put("universeKind", rs.getString("universe_kind"));
                strategy.put("conditionMode", "ALL".equals(rs.getString("root_logic")) ? "ALL" : "ANY");
                String[] codes = (String[]) rs.getArray("indicator_codes").getArray();
                strategy.put("indicatorIds", List.of(codes));
                return strategy;
            }, publicProfileId);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "조합 snapshot 필드가 없습니다: " + field);
        return value;
    }

}
