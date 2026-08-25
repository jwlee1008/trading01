package com.signallab.worker.domain.signal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.worker.global.config.WorkerProperties;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** Reads finalized D1 strategies and completed DB candles, then persists new BUY transitions. */
@Service
public class PostgresDailySignalCycle {

    private final ObjectMapper objectMapper;
    private final DailyStrategyEvaluator evaluator;
    private final PostgresSignalOutboxWriter signalWriter;
    private final DataSource dataSource;

    public PostgresDailySignalCycle(ObjectMapper objectMapper, DailyStrategyEvaluator evaluator,
                                    PostgresSignalOutboxWriter signalWriter, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.evaluator = evaluator;
        this.signalWriter = signalWriter;
        this.dataSource = dataSource;
    }

    public CycleReport run(WorkerProperties properties) {
        if (!properties.isEnabled() || !properties.isDailyCycleEnabled()) return new CycleReport(0, 0, 0, "disabled");
        LocalDate through = resolveThrough(properties.getExpectedThrough());
        int lookback = bounded(properties.getCandleLookback(), 500, 30, 2_000);
        try (Connection connection = dataSource.getConnection()) {
            List<StrategyWork> work = loadWork(connection, through);
            int evaluated = 0;
            int signals = 0;
            int outbox = 0;
            for (StrategyWork item : work) {
                List<DailyStrategyEvaluator.Candle> candles = loadCandles(connection, item.instrumentId(), through, lookback);
                if (candles.size() < 2 || candles.getLast().volume() == 0) continue;
                evaluated++;
                DailyStrategyEvaluator.Evaluation result = evaluator.evaluateLatestTransition(candles, item.strategy());
                if (!result.transitionedToMatch()) continue;
                DailyStrategyEvaluator.Candle latest = candles.getLast();
                String evidence = objectMapper.writeValueAsString(signalEvidence(result, item.strategy(), latest, item.symbol()));
                String payload = objectMapper.writeValueAsString(Map.of(
                    "strategyVersionId", item.strategyVersionId().toString(), "symbol", item.symbol(), "candleClose", latest.sessionDate(), "type", "BUY_CONDITION"
                ));
                boolean testFixture = item.symbol().startsWith("TST");
                String datasetVersion = (testFixture ? "local-test-fixture:" : "spring-worker:db-candles:") + latest.sessionDate();
                BigDecimal signalStrength = BigDecimal.ONE;
                BigDecimal priorLiquidity = BigDecimal.valueOf(candles.stream()
                    .limit(Math.max(0, candles.size() - 1L)).skip(Math.max(0, candles.size() - 21L))
                    .mapToLong(DailyStrategyEvaluator.Candle::volume).average().orElse(0d));
                PostgresSignalOutboxWriter.PersistResult saved = signalWriter.persist(connection,
                    new PostgresSignalOutboxWriter.BuySignal(item.userId(), item.strategyVersionId(), item.instrumentId(),
                        LocalDate.parse(latest.sessionDate()).atTime(15, 30).atOffset(ZoneOffset.ofHours(9)), signalStrength, priorLiquidity,
                        evidence, datasetVersion, item.engineVersion(), "push:buy:" + item.strategyVersionId() + ":" + item.instrumentId() + ":" + latest.sessionDate(), payload));
                if (saved.signalCreated()) signals++;
                if (saved.outboxCreated()) outbox++;
            }
            return new CycleReport(evaluated, signals, outbox, "postgres");
        } catch (Exception error) {
            throw new IllegalStateException("Spring daily signal cycle failed", error);
        }
    }

    private Map<String, Object> signalEvidence(DailyStrategyEvaluator.Evaluation result,
                                               DailyStrategyEvaluator.Strategy strategy,
                                               DailyStrategyEvaluator.Candle latest, String symbol) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("closePrice", BigDecimal.valueOf(latest.close()));
        List<Map<String, String>> reasons = new ArrayList<>();
        String sourceLabel = symbol.startsWith("TST") ? "로컬 테스트 fixture" : "키움 OpenAPI 일봉";
        evidence.put("dataSource", sourceLabel);
        reasons.add(Map.of("label", "데이터 출처", "value", sourceLabel));
        for (int index = 0; index < strategy.rules().size(); index++) {
            String prefix = "rule." + index;
            if (!Boolean.TRUE.equals(result.evidence().get(prefix + ".matched"))) continue;
            Object left = result.evidence().get(prefix + ".left");
            Object right = result.evidence().get(prefix + ".right");
            reasons.add(Map.of(
                "label", ruleLabel(strategy.rules().get(index), index),
                "value", displayValue(left) + " / 기준 " + displayValue(right)
            ));
        }
        evidence.put("reasons", reasons);
        evidence.put("calculations", result.evidence());
        return evidence;
    }

    private String ruleLabel(DailyStrategyEvaluator.Rule rule, int index) {
        return "규칙 " + (index + 1) + " " + operandLabel(rule.left()) + " " + switch (rule.operator()) {
            case GT -> "초과"; case GTE -> "이상"; case LT -> "미만"; case LTE -> "이하"; case EQ -> "일치";
            case CROSSES_ABOVE -> "상향 돌파"; case CROSSES_BELOW -> "하향 돌파";
        };
    }

    private String operandLabel(DailyStrategyEvaluator.Operand operand) {
        if (operand instanceof DailyStrategyEvaluator.Close) return "종가";
        if (operand instanceof DailyStrategyEvaluator.Value) return "기준값";
        DailyStrategyEvaluator.Indicator indicator = (DailyStrategyEvaluator.Indicator) operand;
        return indicator.code() + (indicator.outputKey().isBlank() ? "" : "(" + indicator.outputKey() + ")");
    }

    private String displayValue(Object value) {
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString();
        return "-";
    }

    private List<StrategyWork> loadWork(Connection connection, LocalDate through) throws Exception {
        String sql = """
            SELECT sv.id AS strategy_version_id, sv.user_id, sv.root_logic, sv.engine_version,
                   i.id AS instrument_id, i.symbol, sr.rule_index, sr.operator, sr.params
              FROM public.strategy_versions sv
              JOIN public.strategies strategy ON strategy.id = sv.strategy_id
              JOIN public.strategy_rules sr ON sr.strategy_version_id = sv.id
              JOIN public.universe_memberships um ON um.universe_version_id = sv.universe_version_id
              JOIN public.instruments i ON i.id = um.instrument_id
             WHERE sv.finalized_at IS NOT NULL AND sv.timeframe = 'D1' AND sv.notifications_enabled
               AND strategy.archived_at IS NULL
               AND NOT EXISTS (
                 SELECT 1 FROM public.strategy_versions newer
                  WHERE newer.strategy_id=sv.strategy_id AND newer.finalized_at IS NOT NULL AND newer.version>sv.version
               )
               AND i.is_trade_suspended = false AND um.effective_from <= ?
               AND (um.effective_to IS NULL OR um.effective_to >= ?)
             ORDER BY sv.id, i.symbol, sr.rule_index
            """;
        Map<String, WorkBuilder> grouped = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, through);
            statement.setObject(2, through);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID strategyId = rows.getObject("strategy_version_id", UUID.class);
                    UUID instrumentId = rows.getObject("instrument_id", UUID.class);
                    String key = strategyId + ":" + instrumentId;
                    WorkBuilder builder = grouped.computeIfAbsent(key, unused -> new WorkBuilder(strategyId,
                        rowsUncheckedUuid(rows, "user_id"), rowsUncheckedUuid(rows, "instrument_id"), rowsUncheckedString(rows, "symbol"),
                        rowsUncheckedString(rows, "root_logic"), rowsUncheckedString(rows, "engine_version")));
                    builder.rules.add(parseRule(rows.getString("operator"), rows.getString("params")));
                }
            }
        }
        List<StrategyWork> result = new ArrayList<>();
        for (WorkBuilder builder : grouped.values()) {
            result.add(new StrategyWork(builder.strategyVersionId, builder.userId, builder.instrumentId, builder.symbol, builder.engineVersion,
                new DailyStrategyEvaluator.Strategy("ALL".equals(builder.rootLogic) ? DailyStrategyEvaluator.Logic.AND : DailyStrategyEvaluator.Logic.OR, builder.rules)));
        }
        return result;
    }

    private List<DailyStrategyEvaluator.Candle> loadCandles(Connection connection, UUID instrumentId, LocalDate through, int limit) throws Exception {
        String sql = """
            SELECT session_date, open, high, low, close, adjusted_close, COALESCE(volume, 0) AS volume
              FROM (SELECT session_date, open, high, low, close, adjusted_close, volume, close_at FROM public.candles
                     WHERE instrument_id = ? AND timeframe = 'D1' AND is_final AND NOT is_stale AND session_date <= ?
                     ORDER BY close_at DESC LIMIT ?) latest
             ORDER BY session_date ASC
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, instrumentId);
            statement.setObject(2, through);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<DailyStrategyEvaluator.Candle> candles = new ArrayList<>();
                while (rows.next()) {
                    double close = rows.getBigDecimal("close").doubleValue();
                    double factor = rows.getBigDecimal("adjusted_close") == null ? 1d : rows.getBigDecimal("adjusted_close").doubleValue() / close;
                    candles.add(new DailyStrategyEvaluator.Candle(rows.getObject("session_date", LocalDate.class).toString(),
                        rows.getBigDecimal("open").doubleValue() * factor, rows.getBigDecimal("high").doubleValue() * factor,
                        rows.getBigDecimal("low").doubleValue() * factor, close * factor, rows.getBigDecimal("volume").longValueExact()));
                }
                return candles;
            }
        }
    }

    private DailyStrategyEvaluator.Rule parseRule(String operator, String rawParams) throws Exception {
        JsonNode source = objectMapper.readTree(rawParams).path("sourceRule");
        if (!source.isObject()) throw new IllegalArgumentException("Strategy rule is missing sourceRule");
        return new DailyStrategyEvaluator.Rule(operand(source.path("left")), DailyStrategyEvaluator.Operator.valueOf(operator), operand(source.path("right")));
    }

    private DailyStrategyEvaluator.Operand operand(JsonNode node) {
        return switch (node.path("kind").asText()) {
            case "CLOSE" -> new DailyStrategyEvaluator.Close();
            case "VALUE" -> new DailyStrategyEvaluator.Value(node.path("value").asDouble(Double.NaN));
            case "INDICATOR" -> {
                DailyStrategyEvaluator.Code code = DailyStrategyEvaluator.Code.valueOf(node.path("indicatorId").asText());
                Map<String, Double> params = new LinkedHashMap<>();
                node.path("params").fields().forEachRemaining(entry -> params.put(entry.getKey(), entry.getValue().asDouble(Double.NaN)));
                if (params.values().stream().anyMatch(value -> !Double.isFinite(value))) throw new IllegalArgumentException("Invalid indicator parameter");
                yield new DailyStrategyEvaluator.Indicator(code, node.path("outputKey").asText(defaultOutputKey(code)), params);
            }
            default -> throw new IllegalArgumentException("Unsupported strategy operand");
        };
    }

    private String defaultOutputKey(DailyStrategyEvaluator.Code code) {
        return switch (code) {
            case SMA -> "sma"; case EMA -> "ema"; case RSI -> "rsi"; case MACD -> "macd";
            case BOLLINGER -> "middle"; case VOLUME_SPIKE -> "ratio"; case STOCHASTIC -> "k";
            case ATR -> "atr"; case ADX -> "adx"; case OBV -> "obv";
            default -> throw new IllegalArgumentException("Unsupported Spring worker indicator: " + code);
        };
    }

    private LocalDate resolveThrough(String configured) {
        if (configured != null && !configured.isBlank()) return LocalDate.parse(configured);
        return OffsetDateTime.now(ZoneOffset.ofHours(9)).toLocalDate();
    }
    private static int bounded(int value, int fallback, int minimum, int maximum) {
        int result = value == 0 ? fallback : value;
        if (result < minimum || result > maximum) throw new IllegalArgumentException("candleLookback must be between " + minimum + " and " + maximum);
        return result;
    }
    private static UUID rowsUncheckedUuid(ResultSet rows, String column) { try { return rows.getObject(column, UUID.class); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static String rowsUncheckedString(ResultSet rows, String column) { try { return rows.getString(column); } catch (Exception error) { throw new IllegalStateException(error); } }

    private static final class WorkBuilder {
        private final UUID strategyVersionId; private final UUID userId; private final UUID instrumentId; private final String symbol; private final String rootLogic; private final String engineVersion;
        private final List<DailyStrategyEvaluator.Rule> rules = new ArrayList<>();
        private WorkBuilder(UUID strategyVersionId, UUID userId, UUID instrumentId, String symbol, String rootLogic, String engineVersion) {
            this.strategyVersionId = strategyVersionId; this.userId = userId; this.instrumentId = instrumentId; this.symbol = symbol; this.rootLogic = rootLogic; this.engineVersion = engineVersion;
        }
    }
    private record StrategyWork(UUID strategyVersionId, UUID userId, UUID instrumentId, String symbol, String engineVersion, DailyStrategyEvaluator.Strategy strategy) {}
    public record CycleReport(int evaluated, int signalsCreated, int outboxCreated, String source) {}
}
