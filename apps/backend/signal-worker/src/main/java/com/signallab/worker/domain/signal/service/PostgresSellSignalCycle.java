package com.signallab.worker.domain.signal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.worker.global.config.WorkerProperties;
import com.signallab.worker.global.config.RuntimeEnvironment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.springframework.stereotype.Service;

/** Evaluates active position sell rules on the latest completed D1 candle. */
@Service
public class PostgresSellSignalCycle {
    private final ObjectMapper mapper;
    private final SellRuleEvaluator sellEvaluator;
    private final DailyStrategyEvaluator technicalEvaluator;

    public PostgresSellSignalCycle(ObjectMapper mapper) {
        this.mapper = mapper;
        this.sellEvaluator = new SellRuleEvaluator();
        this.technicalEvaluator = new DailyStrategyEvaluator();
    }

    public Report run(WorkerProperties properties) {
        if (!properties.isEnabled() || !properties.isSellCycleEnabled()) return new Report(0, 0, 0, 0, "disabled");
        String databaseUrl = RuntimeEnvironment.get("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) throw new IllegalStateException("DATABASE_URL is required for sell cycle");
        LocalDate through = properties.getExpectedThrough() == null || properties.getExpectedThrough().isBlank()
            ? OffsetDateTime.now(ZoneOffset.ofHours(9)).toLocalDate() : LocalDate.parse(properties.getExpectedThrough());
        int evaluated = 0, created = 0, outbox = 0, rankedOrders = 0;
        try (Connection connection = DriverManager.getConnection(jdbcUrl(databaseUrl))) {
            for (Work work : loadWork(connection, through)) {
                List<DailyStrategyEvaluator.Candle> candles = loadCandles(connection, work.instrumentId(), through,
                    Math.max(30, properties.getCandleLookback()));
                if (candles.isEmpty()) continue;
                List<Boolean> technicalMatches = evaluateTechnical(candles, work.technicalConditions());
                evaluated++;
                SellRuleEvaluator.Evaluation result = sellEvaluator.evaluate(new SellRuleEvaluator.Input(
                    work.close(), work.averageCost(), work.highestClose().max(work.close()), work.holdingSessions(),
                    work.stopLossRate(), work.takeProfitRate(), work.trailingStopRate(), work.maxHoldingSessions(),
                    technicalMatches, technicalMatches.isEmpty() ? null : SellRuleEvaluator.Logic.valueOf(work.technicalLogic())
                ));
                SaveResult saved = persist(connection, work, result);
                if (saved.signalCreated()) created++;
                if (saved.outboxCreated()) outbox++;
                if (saved.rankedOrderCreated()) rankedOrders++;
            }
            return new Report(evaluated, created, outbox, rankedOrders, "postgres");
        } catch (Exception error) { throw new IllegalStateException("Spring sell signal cycle failed", error); }
    }

    private List<Work> loadWork(Connection connection, LocalDate through) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT p.id position_id, p.user_id, p.portfolio_id, p.portfolio_kind::text, p.instrument_id,
                   p.quantity, p.average_cost, COALESCE(p.highest_completed_close, p.average_cost) highest_close,
                   srv.id sell_rule_version_id, srv.stop_loss_rate, srv.take_profit_rate, srv.trailing_stop_rate,
                   srv.max_holding_sessions, srv.technical_logic::text, srv.technical_conditions::text,
                   c.session_date, c.close_at, c.close,
                   (SELECT count(*) FROM market_sessions ms JOIN instruments mi ON mi.market=ms.market
                     WHERE mi.id=p.instrument_id AND ms.is_trading_day AND ms.session_date >= p.opened_at::date
                       AND ms.session_date <= c.session_date) holding_sessions
              FROM positions p
              JOIN sell_rule_versions srv ON srv.id=p.sell_rule_version_id AND srv.finalized_at IS NOT NULL
              JOIN LATERAL (SELECT session_date, close_at, close FROM candles
                WHERE instrument_id=p.instrument_id AND timeframe='D1' AND is_final AND NOT is_stale AND session_date<=?
                ORDER BY close_at DESC LIMIT 1) c ON true
             WHERE p.status IN ('OPEN','PARTIALLY_CLOSED') AND p.quantity>0 AND NOT srv.manual_only
            """)) {
            statement.setObject(1, through);
            try (ResultSet rs = statement.executeQuery()) {
                List<Work> rows = new ArrayList<>();
                while (rs.next()) rows.add(new Work(
                    rs.getObject("position_id", UUID.class), rs.getObject("user_id", UUID.class),
                    rs.getObject("portfolio_id", UUID.class), rs.getString("portfolio_kind"),
                    rs.getObject("instrument_id", UUID.class), rs.getLong("quantity"), rs.getBigDecimal("average_cost"),
                    rs.getBigDecimal("highest_close"), rs.getObject("sell_rule_version_id", UUID.class),
                    rs.getBigDecimal("stop_loss_rate"), rs.getBigDecimal("take_profit_rate"),
                    rs.getBigDecimal("trailing_stop_rate"), (Integer) rs.getObject("max_holding_sessions"),
                    rs.getString("technical_logic"), rs.getString("technical_conditions"),
                    rs.getObject("session_date", LocalDate.class), rs.getObject("close_at", OffsetDateTime.class),
                    rs.getBigDecimal("close"), rs.getInt("holding_sessions")
                ));
                return rows;
            }
        }
    }

    private List<DailyStrategyEvaluator.Candle> loadCandles(Connection connection, UUID instrumentId, LocalDate through, int limit) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT session_date, open, high, low, close, COALESCE(volume,0) volume FROM
              (SELECT session_date,open,high,low,close,volume,close_at FROM candles
               WHERE instrument_id=? AND timeframe='D1' AND is_final AND NOT is_stale AND session_date<=?
               ORDER BY close_at DESC LIMIT ?) x ORDER BY session_date
            """)) {
            statement.setObject(1, instrumentId); statement.setObject(2, through); statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<DailyStrategyEvaluator.Candle> rows = new ArrayList<>();
                while (rs.next()) rows.add(new DailyStrategyEvaluator.Candle(rs.getObject("session_date", LocalDate.class).toString(),
                    rs.getBigDecimal("open").doubleValue(), rs.getBigDecimal("high").doubleValue(),
                    rs.getBigDecimal("low").doubleValue(), rs.getBigDecimal("close").doubleValue(), rs.getLong("volume")));
                return rows;
            }
        }
    }

    private List<Boolean> evaluateTechnical(List<DailyStrategyEvaluator.Candle> candles, String raw) throws Exception {
        JsonNode root = mapper.readTree(raw == null ? "[]" : raw);
        List<Boolean> matches = new ArrayList<>();
        for (JsonNode rule : root) {
            DailyStrategyEvaluator.Rule parsed = parseRule(rule);
            matches.add(technicalEvaluator.evaluateLatestTransition(candles,
                new DailyStrategyEvaluator.Strategy(DailyStrategyEvaluator.Logic.AND, List.of(parsed))).currentlyMatched());
        }
        return matches;
    }

    DailyStrategyEvaluator.Rule parseRule(JsonNode rule) {
        if (rule.has("indicatorId")) {
            DailyStrategyEvaluator.Code code = DailyStrategyEvaluator.Code.valueOf(rule.path("indicatorId").asText());
            Map<String, Double> params = new LinkedHashMap<>();
            rule.path("params").fields().forEachRemaining(entry -> params.put(entry.getKey(), entry.getValue().asDouble(Double.NaN)));
            if (params.values().stream().anyMatch(value -> !Double.isFinite(value)))
                throw new IllegalArgumentException("Invalid technical sell parameter");
            return new DailyStrategyEvaluator.Rule(
                new DailyStrategyEvaluator.Indicator(code, rule.path("outputKey").asText(defaultOutput(code)), params),
                DailyStrategyEvaluator.Operator.valueOf(rule.path("operator").asText()),
                new DailyStrategyEvaluator.Value(rule.path("value").asDouble(Double.NaN))
            );
        }
        return new DailyStrategyEvaluator.Rule(operand(rule.path("left")),
            DailyStrategyEvaluator.Operator.valueOf(rule.path("operator").asText()), operand(rule.path("right")));
    }
    private DailyStrategyEvaluator.Operand operand(JsonNode node) {
        return switch (node.path("kind").asText()) {
            case "CLOSE" -> new DailyStrategyEvaluator.Close();
            case "VALUE" -> new DailyStrategyEvaluator.Value(node.path("value").asDouble(Double.NaN));
            case "INDICATOR" -> {
                DailyStrategyEvaluator.Code code = DailyStrategyEvaluator.Code.valueOf(node.path("indicatorId").asText());
                Map<String, Double> params = new LinkedHashMap<>();
                node.path("params").fields().forEachRemaining(entry -> params.put(entry.getKey(), entry.getValue().asDouble()));
                yield new DailyStrategyEvaluator.Indicator(code, node.path("outputKey").asText(defaultOutput(code)), params);
            }
            default -> throw new IllegalArgumentException("Unsupported technical sell operand");
        };
    }
    private String defaultOutput(DailyStrategyEvaluator.Code code) { return switch (code) {
        case SMA -> "sma"; case EMA -> "ema"; case RSI -> "rsi"; case MACD -> "macd"; case BOLLINGER -> "middle";
        case VOLUME_SPIKE -> "ratio"; case STOCHASTIC -> "k"; case ATR -> "atr"; case ADX -> "adx"; case OBV -> "obv";
    }; }

    private SaveResult persist(Connection connection, Work work, SellRuleEvaluator.Evaluation evaluation) throws Exception {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            UUID active = activeSignal(connection, work.positionId(), work.sellRuleVersionId());
            if (!evaluation.triggered()) {
                if (active != null) {
                    update(connection, "UPDATE position_signals SET status='RESOLVED_BY_CONDITION' WHERE id=?", active);
                    update(connection, "UPDATE push_outbox SET status='CANCELLED' WHERE position_signal_id=? AND status IN ('PENDING','FAILED')", active);
                }
                updateHighest(connection, work);
                connection.commit(); return new SaveResult(false, false, false);
            }
            if (active != null) { updateHighest(connection, work); connection.commit(); return new SaveResult(false, false, false); }
            UUID signalId;
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO position_signals (user_id,position_id,sell_rule_version_id,candle_close_at,signal_type,status,
                  reference_close,average_cost,net_return_rate,remaining_quantity,data_is_stale)
                VALUES (?,?,?,?,'SELL_CONDITION','ACTIVE',?,?,?, ?,false)
                ON CONFLICT (position_id,sell_rule_version_id,candle_close_at,signal_type) DO NOTHING RETURNING id
                """)) {
                statement.setObject(1, work.userId()); statement.setObject(2, work.positionId()); statement.setObject(3, work.sellRuleVersionId());
                statement.setObject(4, work.closeAt()); statement.setBigDecimal(5, work.close()); statement.setBigDecimal(6, work.averageCost());
                statement.setBigDecimal(7, netReturn(work)); statement.setLong(8, work.quantity());
                try (ResultSet rs = statement.executeQuery()) { if (!rs.next()) { connection.commit(); return new SaveResult(false,false,false); } signalId=rs.getObject(1,UUID.class); }
            }
            for (SellRuleEvaluator.Match match : evaluation.matches()) try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO signal_rule_matches (user_id,position_signal_id,rule_key,rule_kind,evidence)
                VALUES (?,?,?,?,jsonb_build_object('close',?,'averageCost',?,'sessionDate',?)) ON CONFLICT DO NOTHING
                """)) {
                statement.setObject(1,work.userId()); statement.setObject(2,signalId); statement.setString(3,match.key());
                statement.setString(4,match.kind()); statement.setBigDecimal(5,work.close()); statement.setBigDecimal(6,work.averageCost()); statement.setObject(7,work.sessionDate()); statement.executeUpdate();
            }
            int outbox = insertOutbox(connection, work, signalId, evaluation);
            int ranked = "RANKED_PAPER".equals(work.portfolioKind()) ? insertRankedSell(connection, work, signalId) : 0;
            updateHighest(connection, work);
            connection.commit(); return new SaveResult(true, outbox == 1, ranked == 1);
        } catch (Exception error) { connection.rollback(); throw error; }
        finally { connection.setAutoCommit(oldAutoCommit); }
    }

    private UUID activeSignal(Connection c, UUID positionId, UUID ruleId) throws Exception {
        try (PreparedStatement s=c.prepareStatement("SELECT id FROM position_signals WHERE position_id=? AND sell_rule_version_id=? AND status='ACTIVE' ORDER BY created_at DESC LIMIT 1")) {
            s.setObject(1,positionId); s.setObject(2,ruleId); try(ResultSet r=s.executeQuery()){return r.next()?r.getObject(1,UUID.class):null;}
        }
    }
    private int insertOutbox(Connection c, Work w, UUID signalId, SellRuleEvaluator.Evaluation e) throws Exception {
        String payload=mapper.writeValueAsString(Map.of("type","SELL_CONDITION","positionId",w.positionId().toString(),"sessionDate",w.sessionDate().toString(),"matches",e.matches().stream().map(SellRuleEvaluator.Match::key).toList()));
        try(PreparedStatement s=c.prepareStatement("INSERT INTO push_outbox(user_id,position_signal_id,position_id,dedupe_key,status,redacted_payload) VALUES (?,?,?,?,'PENDING',?::jsonb) ON CONFLICT(dedupe_key) DO NOTHING")){
            s.setObject(1,w.userId());s.setObject(2,signalId);s.setObject(3,w.positionId());s.setString(4,"push:sell:"+w.positionId()+":"+w.sessionDate());s.setString(5,payload);return s.executeUpdate();}
    }
    private int insertRankedSell(Connection c, Work w, UUID signalId) throws Exception {
        try(PreparedStatement s=c.prepareStatement("""
            INSERT INTO paper_orders(user_id,portfolio_id,portfolio_kind,position_id,instrument_id,side,quantity,status,
              scheduled_market_session_id,source_position_signal_id,fill_model_version_id,cost_model_version_id,can_user_cancel,idempotency_key)
            SELECT ?,?,'RANKED_PAPER',?,?,'SELL',?,'PENDING',ms.id,?,fm.id,cm.id,false,?
              FROM LATERAL (SELECT mi.id FROM market_sessions mi JOIN instruments i ON i.market=mi.market WHERE i.id=? AND mi.is_trading_day AND mi.session_date>? ORDER BY mi.session_date LIMIT 1) ms,
                   LATERAL (SELECT id FROM paper_fill_model_versions WHERE effective_from<=now() ORDER BY effective_from DESC,version DESC LIMIT 1) fm,
                   LATERAL (SELECT id FROM cost_model_versions WHERE effective_from<=now() ORDER BY effective_from DESC,version DESC LIMIT 1) cm
            ON CONFLICT(portfolio_id,idempotency_key) DO NOTHING
            """)){
            s.setObject(1,w.userId());s.setObject(2,w.portfolioId());s.setObject(3,w.positionId());s.setObject(4,w.instrumentId());s.setLong(5,w.quantity());s.setObject(6,signalId);
            s.setString(7,"ranked-sell:"+signalId);s.setObject(8,w.instrumentId());s.setObject(9,w.sessionDate());int count=s.executeUpdate();
            if(count==0) throw new IllegalStateException("Ranked SELL requires next market session and active model versions");
            update(c,"UPDATE positions SET status='EXIT_PENDING',updated_at=now() WHERE id=? AND status IN ('OPEN','PARTIALLY_CLOSED')",w.positionId());return count;}
    }
    private void updateHighest(Connection c,Work w)throws Exception{try(PreparedStatement s=c.prepareStatement("UPDATE positions SET highest_completed_close=greatest(COALESCE(highest_completed_close,0),?),updated_at=now() WHERE id=?")){s.setBigDecimal(1,w.close());s.setObject(2,w.positionId());s.executeUpdate();}}
    private void update(Connection c,String sql,UUID id)throws Exception{try(PreparedStatement s=c.prepareStatement(sql)){s.setObject(1,id);s.executeUpdate();}}
    private BigDecimal netReturn(Work w){if(w.averageCost().signum()==0)return BigDecimal.ZERO;return w.close().divide(w.averageCost(),10,RoundingMode.HALF_UP).subtract(BigDecimal.ONE);}
    private static String jdbcUrl(String url){return url.startsWith("postgresql://")?"jdbc:"+url:url;}

    private record Work(UUID positionId,UUID userId,UUID portfolioId,String portfolioKind,UUID instrumentId,long quantity,
      BigDecimal averageCost,BigDecimal highestClose,UUID sellRuleVersionId,BigDecimal stopLossRate,BigDecimal takeProfitRate,
      BigDecimal trailingStopRate,Integer maxHoldingSessions,String technicalLogic,String technicalConditions,LocalDate sessionDate,
      OffsetDateTime closeAt,BigDecimal close,int holdingSessions){}
    private record SaveResult(boolean signalCreated,boolean outboxCreated,boolean rankedOrderCreated){}
    public record Report(int evaluated,int signalsCreated,int outboxCreated,int rankedOrdersCreated,String source){}
}
