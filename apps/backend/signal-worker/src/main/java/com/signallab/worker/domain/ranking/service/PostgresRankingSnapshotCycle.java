package com.signallab.worker.domain.ranking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.signallab.worker.global.config.WorkerProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds real period-specific rankings from persisted signals and finalized D1 candles. */
@Service
public class PostgresRankingSnapshotCycle {
    private static final int MINIMUM_SIGNALS = 30;
    private static final String FORMULA_VERSION = "signal-forward20-net-v1";
    private static final String ENGINE_VERSION = "ranking-snapshot-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresRankingSnapshotCycle(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Report run(WorkerProperties properties) {
        if (!properties.isEnabled() || !properties.isRankingSnapshotEnabled()) return new Report(0, 0, 0, "disabled");
        ModelVersions models = modelVersions();
        SnapshotClock clock = snapshotClock();
        if (clock == null) return new Report(0, 0, 0, "no-final-candles");
        int published = 0, combinations = 0, tiers = 0;
        for (Period period : Period.values()) {
            List<Event> events = events(clock.sessionDate().minusMonths(period.months()));
            List<Combination> metrics = combinations(events, models.roundTripCostPercent(), period);
            combinations += metrics.size();
            ArrayNode rows = mapper.createArrayNode();
            for (int index = 0; index < metrics.size(); index++) rows.add(metrics.get(index).json(index + 1));
            jdbc.update("""
                INSERT INTO ranking_snapshots(kind,period,as_of,formula_version,dataset_version,engine_version,
                  cost_model_version_id,fill_model_version_id,rows,is_published)
                VALUES ('COMBINATION',?::ranking_period,?,?,?,?,?,?,?::jsonb,true)
                ON CONFLICT(kind,period,as_of,formula_version) DO UPDATE SET rows=excluded.rows,is_published=true,
                  dataset_version=excluded.dataset_version,engine_version=excluded.engine_version
                """, period.db(), Timestamp.from(clock.asOf()), FORMULA_VERSION, clock.datasetVersion(), ENGINE_VERSION,
                models.costId(), models.fillId(), rows.toString());
            tiers += persistIndicatorTiers(metrics, period, clock);
            published++;
        }
        return new Report(published, combinations, tiers, "postgres");
    }

    private List<Event> events(LocalDate start) {
        return jdbc.query("""
            WITH meta AS (
              SELECT sv.id strategy_version_id,sv.universe_version_id,ud.kind::text universe_kind,sv.root_logic::text root_logic,
                     s.name, jsonb_agg(sr.params->'sourceRule' ORDER BY sr.rule_index)::text rules,
                     array_agg(i.code ORDER BY sr.rule_index) indicators
              FROM strategy_versions sv JOIN strategies s ON s.id=sv.strategy_id
              JOIN universe_versions uv ON uv.id=sv.universe_version_id JOIN universe_definitions ud ON ud.id=uv.universe_definition_id
              JOIN strategy_rules sr ON sr.strategy_version_id=sv.id JOIN indicator_definitions i ON i.id=sr.indicator_definition_id
              WHERE sv.finalized_at IS NOT NULL AND s.archived_at IS NULL
              GROUP BY sv.id,sv.universe_version_id,ud.kind,sv.root_logic,s.name
            )
            SELECT m.strategy_version_id,m.universe_version_id,m.universe_kind,m.root_logic,m.name,m.rules,m.indicators,
                   s.id signal_id,s.instrument_id,s.candle_close_at,entry.open entry_open,exit.close exit_close,exit.session_date exit_date
            FROM signals s JOIN meta m ON m.strategy_version_id=s.strategy_version_id
            JOIN LATERAL (SELECT c.open,c.session_date FROM candles c WHERE c.instrument_id=s.instrument_id AND c.is_final AND NOT c.is_stale
                          AND c.close_at>s.candle_close_at ORDER BY c.session_date LIMIT 1) entry ON true
            JOIN LATERAL (SELECT c.close,c.session_date FROM candles c WHERE c.instrument_id=s.instrument_id AND c.is_final AND NOT c.is_stale
                          AND c.session_date>=entry.session_date ORDER BY c.session_date OFFSET 19 LIMIT 1) exit ON true
            WHERE s.candle_close_at>=? AND NOT s.data_is_stale
            ORDER BY exit.session_date,s.id
            """, (rs, index) -> new Event(
                UUID.fromString(rs.getString("strategy_version_id")), UUID.fromString(rs.getString("universe_version_id")),
                rs.getString("universe_kind"), rs.getString("root_logic"), rs.getString("name"), rs.getString("rules"),
                List.of((String[]) rs.getArray("indicators").getArray()), UUID.fromString(rs.getString("instrument_id")),
                rs.getBigDecimal("entry_open"), rs.getBigDecimal("exit_close"), rs.getDate("exit_date").toLocalDate()), start);
    }

    private List<Combination> combinations(List<Event> events, BigDecimal costPercent, Period period) {
        Map<String, List<Event>> grouped = new LinkedHashMap<>();
        for (Event event : events) grouped.computeIfAbsent(event.identity(), unused -> new ArrayList<>()).add(event);
        List<Combination> result = new ArrayList<>();
        for (List<Event> group : grouped.values()) {
            if (group.size() < MINIMUM_SIGNALS) continue;
            Event first = group.getFirst();
            List<Double> returns = group.stream().map(event -> event.exitClose().subtract(event.entryOpen())
                .divide(event.entryOpen(), 12, RoundingMode.HALF_UP).movePointRight(2).subtract(costPercent).doubleValue()).toList();
            double average = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double hitRate = returns.stream().filter(value -> value > 0).count() * 100d / returns.size();
            double deviation = Math.sqrt(returns.stream().mapToDouble(value -> Math.pow(value - average, 2)).average().orElse(0));
            double equity = 1, peak = 1, mdd = 0;
            for (double value : returns) { equity *= 1 + value / 100; peak = Math.max(peak, equity); mdd = Math.min(mdd, (equity - peak) / peak * 100); }
            double margin = 1.96 * deviation / Math.sqrt(returns.size());
            double stability = Math.max(0, Math.min(100, 100 / (1 + deviation)));
            double score = Math.max(0, Math.min(100, 50 + average * 5 + (hitRate - 50) * .4 + mdd * .3));
            result.add(new Combination(first, period, average, hitRate, mdd, stability, score,
                average - margin, average + margin, group.size(), (int) group.stream().map(Event::instrumentId).distinct().count()));
        }
        result.sort(Comparator.comparingDouble(Combination::score).reversed()
            .thenComparing(Comparator.comparingDouble(Combination::mdd).reversed()));
        return List.copyOf(result);
    }

    private int persistIndicatorTiers(List<Combination> combinations, Period period, SnapshotClock clock) {
        Map<IndicatorUniverse, List<Combination>> groups = new LinkedHashMap<>();
        for (Combination combination : combinations) for (String indicator : combination.event().indicators()) {
            groups.computeIfAbsent(new IndicatorUniverse(indicator, combination.event().universeVersionId()), unused -> new ArrayList<>()).add(combination);
        }
        int count = 0;
        for (Map.Entry<IndicatorUniverse, List<Combination>> entry : groups.entrySet()) {
            int samples = entry.getValue().stream().mapToInt(Combination::signals).sum();
            double score = entry.getValue().stream().mapToDouble(Combination::score).average().orElse(0);
            String tier = samples < MINIMUM_SIGNALS ? "INSUFFICIENT_DATA" : score >= 85 ? "S" : score >= 70 ? "A" : score >= 55 ? "B" : "C";
            Integer updated = jdbc.update("""
                INSERT INTO indicator_tier_snapshots(indicator_definition_id,universe_version_id,period,tier,
                  stability_score,total_score,sample_count,formula_version,dataset_version,is_published,as_of)
                SELECT i.id,?,?::ranking_period,?::text,?,?,?,?::text,?::text,true,?
                FROM indicator_definitions i WHERE i.code=? AND i.is_active ORDER BY i.version DESC LIMIT 1
                ON CONFLICT(indicator_definition_id,universe_version_id,period,as_of,formula_version)
                DO UPDATE SET tier=excluded.tier,stability_score=excluded.stability_score,total_score=excluded.total_score,
                  sample_count=excluded.sample_count,is_published=true
                """, entry.getKey().universeVersionId(), period.db(), tier, score / 100, score, samples,
                FORMULA_VERSION, clock.datasetVersion(), Timestamp.from(clock.asOf()), entry.getKey().indicator());
            count += updated;
        }
        return count;
    }

    private ModelVersions modelVersions() {
        return jdbc.query("""
            SELECT cm.id cost_id,pfm.id fill_id,
                   (cm.buy_fee_rate+cm.sell_fee_rate+cm.sell_tax_rate)*100 cost_pct
            FROM cost_model_versions cm CROSS JOIN paper_fill_model_versions pfm
            ORDER BY cm.effective_from DESC,pfm.effective_from DESC LIMIT 1
            """, rs -> {
                if (!rs.next()) throw new IllegalStateException("랭킹 계산에 필요한 비용·체결 모델이 없습니다.");
                return new ModelVersions(UUID.fromString(rs.getString("cost_id")), UUID.fromString(rs.getString("fill_id")), rs.getBigDecimal("cost_pct"));
            });
    }

    private SnapshotClock snapshotClock() {
        return jdbc.query("""
            SELECT close_at,session_date,dataset_version FROM candles WHERE is_final AND NOT is_stale
            ORDER BY session_date DESC,received_at DESC LIMIT 1
            """, rs -> rs.next() ? new SnapshotClock(rs.getTimestamp("close_at").toInstant(), rs.getDate("session_date").toLocalDate(), rs.getString("dataset_version")) : null);
    }

    private static String universeId(String kind) {
        return switch (kind) {
            case "KOSPI_200" -> "kospi200"; case "KOSDAQ_150" -> "kosdaq150"; case "KOSPI_TOP_10" -> "kospiTop10";
            case "KOSPI_ALL" -> "kospi"; case "KOSDAQ_ALL" -> "kosdaq"; case "CUSTOM" -> "custom"; default -> "all";
        };
    }

    private static String indicatorId(String code) {
        return "VOLUME_SPIKE".equals(code) ? "volume" : code.toLowerCase(java.util.Locale.ROOT);
    }

    private enum Period { M3(3, "M3", "3m"), M6(6, "M6", "6m"), Y1(12, "Y1", "1y");
        private final int months; private final String db; private final String key;
        Period(int months, String db, String key) { this.months=months; this.db=db; this.key=key; }
        int months() { return months; } String db() { return db; } String key() { return key; }
    }
    private record Event(UUID strategyVersionId, UUID universeVersionId, String universeKind, String logic, String name,
                         String rules, List<String> indicators, UUID instrumentId, BigDecimal entryOpen, BigDecimal exitClose, LocalDate exitDate) {
        String identity() { return universeVersionId + "|" + logic + "|" + rules; }
    }
    private record Combination(Event event, Period period, double average, double hitRate, double mdd, double stability,
                               double score, double confidenceLow, double confidenceHigh, int signals, int instruments) {
        ObjectNode json(int rank) {
            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode node = objectMapper.createObjectNode();
            String identity = event.identity();
            node.put("id", UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString());
            node.put("rank", rank); node.put("name", event.name()); node.put("universeId", universeId(event.universeKind()));
            node.put("universeVersionId", event.universeVersionId().toString()); node.put("logic", "ALL".equals(event.logic()) ? "AND" : "OR");
            ArrayNode indicatorsNode = node.putArray("indicatorIds"); event.indicators().forEach(value -> indicatorsNode.add(indicatorId(value)));
            try { node.set("rules", objectMapper.readTree(event.rules())); } catch (Exception error) { throw new IllegalStateException("전략 규칙 JSON 오류", error); }
            ObjectNode returns = node.putObject("excessReturn"); returns.put("3m", period.key().equals("3m") ? average : 0); returns.put("6m", period.key().equals("6m") ? average : 0); returns.put("1y", period.key().equals("1y") ? average : 0);
            node.put("hitRate", hitRate); node.put("mdd", mdd); node.put("signalCount", signals); node.put("instrumentCount", instruments);
            node.put("stability", stability); node.put("score", score); node.put("confidence", String.format(java.util.Locale.ROOT, "%.2f%% ~ %.2f%%", confidenceLow, confidenceHigh));
            return node;
        }
    }
    private record IndicatorUniverse(String indicator, UUID universeVersionId) {}
    private record ModelVersions(UUID costId, UUID fillId, BigDecimal roundTripCostPercent) {}
    private record SnapshotClock(Instant asOf, LocalDate sessionDate, String datasetVersion) {}
    public record Report(int periods, int combinations, int indicatorTiers, String source) {}
}
