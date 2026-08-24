package com.signallab.worker.domain.ranking.service;

import com.signallab.worker.global.config.WorkerProperties;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records official-track NAV from finalized closes and updates ranking metrics idempotently. */
@Service
public class PostgresRankingNavCycle {
    private final JdbcTemplate jdbc;
    public PostgresRankingNavCycle(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Report run(WorkerProperties properties) {
        if (!properties.isEnabled() || !properties.isRankingNavEnabled()) return new Report(0, "disabled");
        Valuation valuation = jdbc.query("""
            SELECT close_at,dataset_version FROM candles WHERE is_final AND NOT is_stale
            ORDER BY session_date DESC,received_at DESC LIMIT 1
            """, rs -> rs.next() ? new Valuation(rs.getTimestamp("close_at"), rs.getString("dataset_version")) : null);
        if (valuation == null) return new Report(0, "no-final-candles");
        List<UUID> tracks = jdbc.query("SELECT id FROM ranking_tracks WHERE status='ACTIVE' ORDER BY id",
            (rs, index) -> UUID.fromString(rs.getString(1)));
        for (UUID trackId : tracks) record(trackId, valuation);
        return new Report(tracks.size(), "postgres");
    }

    private void record(UUID trackId, Valuation valuation) {
        jdbc.update("""
            WITH values AS (
              SELECT rt.user_id,rt.portfolio_id,rt.id ranking_track_id,p.cash_balance cash,
                     COALESCE(sum(pos.quantity*latest.close),0) market_value,
                     COALESCE(sum(pos.realized_pnl),0) realized_pnl,
                     COALESCE(sum((latest.close-pos.average_cost)*pos.quantity),0) unrealized_pnl
              FROM ranking_tracks rt JOIN portfolios p ON p.id=rt.portfolio_id
              LEFT JOIN positions pos ON pos.portfolio_id=p.id AND pos.status IN ('OPEN','EXIT_PENDING','PARTIALLY_CLOSED')
              LEFT JOIN LATERAL (SELECT close FROM candles c WHERE c.instrument_id=pos.instrument_id AND c.is_final AND NOT c.is_stale
                                 AND c.close_at<=? ORDER BY c.session_date DESC LIMIT 1) latest ON true
              WHERE rt.id=? GROUP BY rt.user_id,rt.portfolio_id,rt.id,p.cash_balance
            ), costs AS (
              SELECT COALESCE(sum(e.fee),0) fees,COALESCE(sum(e.tax),0) taxes FROM position_executions e
              WHERE e.portfolio_id=(SELECT portfolio_id FROM values) AND e.effect_multiplier=1
            )
            INSERT INTO portfolio_nav_snapshots(user_id,portfolio_id,ranking_track_id,valuation_at,cash,market_value,nav,
              realized_pnl,unrealized_pnl,fees,taxes,data_version)
            SELECT v.user_id,v.portfolio_id,v.ranking_track_id,?,v.cash,v.market_value,v.cash+v.market_value,
                   v.realized_pnl,v.unrealized_pnl,c.fees,c.taxes,? FROM values v CROSS JOIN costs c
            ON CONFLICT(portfolio_id,valuation_at) DO UPDATE SET cash=excluded.cash,market_value=excluded.market_value,
              nav=excluded.nav,realized_pnl=excluded.realized_pnl,unrealized_pnl=excluded.unrealized_pnl,
              fees=excluded.fees,taxes=excluded.taxes,data_version=excluded.data_version
            """, valuation.at(), trackId, valuation.at(), valuation.datasetVersion());
        jdbc.update("""
            WITH series AS (
              SELECT nav,valuation_at,max(nav) over (ORDER BY valuation_at) peak
              FROM portfolio_nav_snapshots WHERE ranking_track_id=?
            ), metrics AS (
              SELECT (SELECT nav FROM series ORDER BY valuation_at DESC LIMIT 1) latest_nav,
                     min(CASE WHEN peak>0 THEN nav/peak-1 ELSE 0 END) mdd
              FROM series
            )
            UPDATE ranking_tracks rt SET cumulative_return=m.latest_nav/rt.initial_capital-1,max_drawdown=m.mdd,
              trade_count=(SELECT count(*) FROM position_executions e WHERE e.portfolio_id=rt.portfolio_id AND e.side='SELL' AND e.event_type='EXECUTION'),
              last_nav_at=?,updated_at=now()
            FROM metrics m WHERE rt.id=?
            """, trackId, valuation.at(), trackId);
        jdbc.update("""
            INSERT INTO ranking_track_events(user_id,ranking_track_id,event_type,payload,idempotency_key,occurred_at)
            SELECT user_id,id,'NAV_RECORDED',jsonb_build_object('valuationAt',?::text),?,? FROM ranking_tracks WHERE id=?
            ON CONFLICT(ranking_track_id,idempotency_key) DO NOTHING
            """, valuation.at(), "ranking-nav:" + valuation.at().toInstant(), valuation.at(), trackId);
    }

    private record Valuation(Timestamp at, String datasetVersion) {}
    public record Report(int tracks, String source) {}
}
