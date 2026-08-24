package com.signallab.api.domain.ranking.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RankingTrackService {
    private static final BigDecimal INITIAL_CAPITAL = new BigDecimal("10000000");
    private final JdbcTemplate jdbc;

    public RankingTrackService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> active(UUID userId) {
        return jdbc.query("""
            SELECT rt.id,rt.strategy_version_id,rt.portfolio_id,rt.status,rt.initial_capital,rt.cumulative_return,
                   rt.max_drawdown,rt.trade_count,rt.is_public,rt.started_at,s.name strategy_name
            FROM ranking_tracks rt JOIN strategy_versions sv ON sv.id=rt.strategy_version_id
            JOIN strategies s ON s.id=sv.strategy_id
            WHERE rt.user_id=? AND rt.status='ACTIVE' ORDER BY rt.started_at DESC LIMIT 1
            """, rs -> rs.next() ? row(rs) : null, userId);
    }

    @Transactional
    public Map<String, Object> start(UUID userId, StartRequest request) {
        if (active(userId) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 활성 공식 랭킹 트랙이 있습니다.");
        OffsetDateTime nextAllowedAt = jdbc.query("""
            SELECT ended_at + make_interval(days => restart_cooldown_days)
            FROM ranking_tracks WHERE user_id=? AND status='ENDED' ORDER BY ended_at DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, OffsetDateTime.class) : null, userId);
        if (nextAllowedAt != null && nextAllowedAt.isAfter(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "공식 랭킹 재시작 가능 시각: " + nextAllowedAt);
        }
        UUID strategyVersionId;
        try { strategyVersionId = UUID.fromString(request.strategyVersionId()); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 버전 ID가 올바르지 않습니다."); }
        StrategyRef strategy = jdbc.query("""
            SELECT sv.id,sv.universe_version_id,s.name FROM strategy_versions sv JOIN strategies s ON s.id=sv.strategy_id
            WHERE sv.id=? AND sv.user_id=? AND sv.finalized_at IS NOT NULL AND s.archived_at IS NULL
              AND sv.version=(SELECT max(v.version) FROM strategy_versions v WHERE v.strategy_id=sv.strategy_id)
            """, rs -> rs.next() ? new StrategyRef(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("universe_version_id")), rs.getString("name")) : null,
            strategyVersionId, userId);
        if (strategy == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "최신 확정 전략 버전만 랭킹에 등록할 수 있습니다.");

        UUID portfolioId = jdbc.queryForObject("""
            INSERT INTO portfolios(user_id,kind,name,initial_cash,cash_balance)
            VALUES (?,'RANKED_PAPER',?, ?, ?) RETURNING id
            """, UUID.class, userId, "공식 랭킹 " + Instant.now(), INITIAL_CAPITAL, INITIAL_CAPITAL);
        jdbc.update("""
            INSERT INTO portfolio_cash_ledger(user_id,portfolio_id,portfolio_kind,sequence_no,event_type,amount,
              resulting_balance,idempotency_key,occurred_at)
            VALUES (?,?,'RANKED_PAPER',1,'INITIAL_CAPITAL',?,?,?,now())
            """, userId, portfolioId, INITIAL_CAPITAL, INITIAL_CAPITAL, "ranking-initial:" + portfolioId);

        UUID sellSetId = jdbc.queryForObject("INSERT INTO sell_rule_sets(user_id,name) VALUES (?,?) RETURNING id",
            UUID.class, userId, "공식 랭킹 기본 청산 " + strategy.name() + " " + UUID.randomUUID().toString().substring(0, 8));
        UUID sellVersionId = jdbc.queryForObject("""
            INSERT INTO sell_rule_versions(user_id,sell_rule_set_id,version,stop_loss_rate,take_profit_rate,
              trailing_stop_rate,max_holding_sessions,technical_logic,technical_conditions,manual_only)
            VALUES (?,?,1,.08,.20,.10,60,null,'[]'::jsonb,false) RETURNING id
            """, UUID.class, userId, sellSetId);
        jdbc.update("UPDATE sell_rule_versions SET finalized_at=now() WHERE id=?", sellVersionId);
        Model model = jdbc.query("""
            SELECT cm.id cost_id,pfm.id fill_id FROM cost_model_versions cm CROSS JOIN paper_fill_model_versions pfm
            ORDER BY cm.effective_from DESC,pfm.effective_from DESC LIMIT 1
            """, rs -> {
                if (!rs.next()) throw new ResponseStatusException(HttpStatus.CONFLICT, "비용·체결 모델이 준비되지 않았습니다.");
                return new Model(UUID.fromString(rs.getString("cost_id")), UUID.fromString(rs.getString("fill_id")));
            });
        UUID trackId = jdbc.queryForObject("""
            INSERT INTO ranking_tracks(user_id,portfolio_id,strategy_version_id,universe_version_id,sell_rule_version_id,
              fill_model_version_id,cost_model_version_id,status,initial_capital,max_position_weight,max_open_positions,
              priority_formula_version,restart_cooldown_days,cumulative_return,max_drawdown,trade_count,is_public)
            VALUES (?,?,?,?,?,?,?,'ACTIVE',?,.10,10,'strength-liquidity-v1',30,0,0,0,?) RETURNING id
            """, UUID.class, userId, portfolioId, strategy.id(), strategy.universeVersionId(), sellVersionId,
            model.fillId(), model.costId(), INITIAL_CAPITAL, request.isPublic());
        jdbc.update("""
            INSERT INTO portfolio_nav_snapshots(user_id,portfolio_id,ranking_track_id,valuation_at,cash,market_value,nav,
              realized_pnl,unrealized_pnl,fees,taxes,data_version)
            VALUES (?,?,?,now(),?,0,?,0,0,0,0,'ranking-initial-v1')
            """, userId, portfolioId, trackId, INITIAL_CAPITAL, INITIAL_CAPITAL);
        jdbc.update("""
            INSERT INTO ranking_track_events(user_id,ranking_track_id,event_type,payload,idempotency_key)
            VALUES (?,?,'STARTED',jsonb_build_object('initialCapital',?,'strategyVersionId',?::text),?)
            """, userId, trackId, INITIAL_CAPITAL, strategy.id(), "ranking-start:" + trackId);
        return active(userId);
    }

    @Transactional
    public void end(UUID userId) {
        UUID trackId = jdbc.query("""
            UPDATE ranking_tracks SET status='ENDED',ended_at=now(),updated_at=now()
            WHERE user_id=? AND status='ACTIVE' RETURNING id
            """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, userId);
        if (trackId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 공식 랭킹 트랙이 없습니다.");
        jdbc.update("""
            INSERT INTO ranking_track_events(user_id,ranking_track_id,event_type,payload,idempotency_key)
            VALUES (?,?,'ENDED','{}'::jsonb,?)
            """, userId, trackId, "ranking-end:" + trackId);
    }

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id")); row.put("strategyVersionId", rs.getString("strategy_version_id"));
        row.put("portfolioId", rs.getString("portfolio_id")); row.put("status", rs.getString("status"));
        row.put("initialCapital", rs.getBigDecimal("initial_capital")); row.put("returnRate", rs.getBigDecimal("cumulative_return"));
        row.put("maxDrawdown", rs.getBigDecimal("max_drawdown")); row.put("tradeCount", rs.getInt("trade_count"));
        row.put("isPublic", rs.getBoolean("is_public")); row.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
        row.put("strategyName", rs.getString("strategy_name"));
        return row;
    }

    public record StartRequest(String strategyVersionId, boolean isPublic) {}
    private record StrategyRef(UUID id, UUID universeVersionId, String name) {}
    private record Model(UUID costId, UUID fillId) {}
}
