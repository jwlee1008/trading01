package com.signallab.api.domain.ranking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.api.domain.strategy.dto.StrategyRequest;
import com.signallab.api.domain.strategy.service.StrategyService;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
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
    private static final Map<String, String> PERIODS = Map.of("3M", "M3", "6M", "M6", "1Y", "Y1");
    private final StrategyService strategyService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RankingService(StrategyService strategyService, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.strategyService = strategyService;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Object copyCombination(UUID userId, String combinationId) {
        JsonNode row = jdbc.query("""
            SELECT elem FROM ranking_snapshots rs
            CROSS JOIN LATERAL jsonb_array_elements(rs.rows) elem
            WHERE rs.kind='COMBINATION' AND rs.is_published AND elem->>'id'=?
            ORDER BY rs.as_of DESC LIMIT 1
            """, result -> result.next() ? parse(result.getString(1)) : null, combinationId);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공개된 조합 snapshot을 찾을 수 없습니다.");
        String name = requiredText(row, "name");
        String universeVersionId = requiredText(row, "universeVersionId");
        JsonNode rawRules = row.path("rules");
        if (!rawRules.isArray() || rawRules.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "조합 snapshot에 복사 가능한 전략 규칙이 없습니다.");
        }
        List<JsonNode> rules = new ArrayList<>();
        rawRules.forEach(rules::add);
        return strategyService.create(userId, new StrategyRequest(
            name + " 복사본", universeVersionId, row.path("logic").asText("AND"), rules, true, false
        ));
    }

    public Map<String, Object> rankings(String period) {
        String requested = period == null || period.isBlank() ? "3M" : period.toUpperCase();
        String dbPeriod = PERIODS.get(requested);
        if (dbPeriod == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 랭킹 기간입니다.");

        List<JsonNode> combinations = jdbc.query("""
            SELECT rows FROM ranking_snapshots
            WHERE kind='COMBINATION' AND period=?::ranking_period AND is_published
            ORDER BY as_of DESC LIMIT 1
            """, result -> {
                if (!result.next()) return List.of();
                JsonNode rows = parse(result.getString(1));
                List<JsonNode> values = new ArrayList<>();
                if (rows.isArray()) rows.forEach(values::add);
                return values;
            }, dbPeriod);

        List<Map<String, Object>> indicatorTiers = jdbc.query("""
            SELECT DISTINCT ON (i.code, its.universe_version_id)
              i.code, i.name_ko, its.tier, its.total_score, its.sample_count, its.as_of
            FROM indicator_tier_snapshots its JOIN indicator_definitions i ON i.id=its.indicator_definition_id
            WHERE its.period=?::ranking_period AND its.is_published
            ORDER BY i.code, its.universe_version_id, its.as_of DESC
            """, (rs, index) -> Map.of(
                "indicatorId", rs.getString("code"), "name", rs.getString("name_ko"),
                "tier", rs.getString("tier"), "score", rs.getBigDecimal("total_score") == null ? 0 : rs.getBigDecimal("total_score"),
                "sampleCount", rs.getInt("sample_count"), "asOf", rs.getTimestamp("as_of").toInstant().toString()
            ), dbPeriod);

        int months = switch (requested) { case "3M" -> 3; case "6M" -> 6; default -> 12; };
        List<Map<String, Object>> users = jdbc.query("""
            WITH eligible AS (
              SELECT rt.id, p.public_profile_id, p.nickname, rt.max_drawdown, rt.trade_count, rt.started_at,
                     latest.nav latest_nav, base.nav base_nav
              FROM ranking_tracks rt JOIN profiles p ON p.user_id=rt.user_id
              JOIN LATERAL (SELECT nav FROM portfolio_nav_snapshots n WHERE n.ranking_track_id=rt.id ORDER BY valuation_at DESC LIMIT 1) latest ON true
              JOIN LATERAL (SELECT nav FROM portfolio_nav_snapshots n WHERE n.ranking_track_id=rt.id AND valuation_at<=now()-make_interval(months => ?) ORDER BY valuation_at DESC LIMIT 1) base ON true
              WHERE rt.is_public AND p.is_public AND p.deleted_at IS NULL
            ), ranked AS (
              SELECT *, (latest_nav/base_nav-1) period_return,
                     rank() over (ORDER BY (latest_nav/base_nav-1) DESC, max_drawdown DESC NULLS LAST, started_at) rank
              FROM eligible WHERE base_nav>0
            ) SELECT * FROM ranked ORDER BY rank LIMIT 100
            """, (rs, index) -> {
                Map<String, Object> returns = new LinkedHashMap<>();
                returns.put("3m", "3M".equals(requested) ? rs.getBigDecimal("period_return").movePointRight(2) : 0);
                returns.put("6m", "6M".equals(requested) ? rs.getBigDecimal("period_return").movePointRight(2) : 0);
                returns.put("1y", "1Y".equals(requested) ? rs.getBigDecimal("period_return").movePointRight(2) : 0);
                returns.put("all", 0);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("public_profile_id")); row.put("rank", rs.getInt("rank"));
                row.put("nickname", rs.getString("nickname")); row.put("returnRate", returns);
                row.put("universeId", "all"); row.put("mdd", rs.getBigDecimal("max_drawdown") == null ? 0 : rs.getBigDecimal("max_drawdown").movePointRight(2));
                row.put("trades", rs.getInt("trade_count"));
                row.put("days", java.time.Duration.between(rs.getTimestamp("started_at").toInstant(), java.time.Instant.now()).toDays());
                row.put("strategyName", "공식 페이퍼 트랙"); row.put("public", true);
                return row;
            }, months);

        return Map.of(
            "period", requested, "combinations", combinations, "indicatorTiers", indicatorTiers, "users", users,
            "disclosure", "공개된 DB ranking snapshot 기준입니다. snapshot이 없으면 빈 목록을 표시합니다.",
            "indicatorDisclosure", "과거 데이터상 견고성 등급이며 미래 수익 예측이 아닙니다."
        );
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (Exception error) { throw new IllegalStateException("랭킹 snapshot JSON을 읽을 수 없습니다.", error); }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "조합 snapshot 필드가 없습니다: " + field);
        return value;
    }
}
