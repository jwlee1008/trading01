package com.signallab.worker.domain.ranking.service;

import com.signallab.worker.global.config.WorkerProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** Creates deterministic D+1 BUY orders for active ranked tracks. */
@Service
public class PostgresRankedBuyCycle {
    private final RankedBuyAllocator allocator = new RankedBuyAllocator();
    private final DataSource dataSource;

    public PostgresRankedBuyCycle(DataSource dataSource) { this.dataSource = dataSource; }

    public Report run(WorkerProperties properties) {
        if (!properties.isEnabled() || !properties.isRankedBuyEnabled()) return new Report(0, 0, "disabled");
        int batch = properties.getRankedBuyBatchSize();
        if (batch < 1 || batch > 5_000) throw new IllegalArgumentException("rankedBuyBatchSize must be within 1..5000");
        LocalDate through = properties.getExpectedThrough() == null || properties.getExpectedThrough().isBlank()
            ? OffsetDateTime.now(ZoneOffset.ofHours(9)).toLocalDate() : LocalDate.parse(properties.getExpectedThrough());
        try (Connection connection = dataSource.getConnection()) {
            List<UUID> tracks = activeTracks(connection);
            int candidates = 0, orders = 0;
            for (UUID trackId : tracks) {
                TrackReport report = processTrack(connection, trackId, through, batch);
                candidates += report.candidates(); orders += report.orders();
            }
            return new Report(candidates, orders, "postgres");
        } catch (Exception error) { throw new IllegalStateException("Spring ranked BUY cycle failed", error); }
    }

    private List<UUID> activeTracks(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM ranking_tracks WHERE status='ACTIVE' ORDER BY id")) {
            try (ResultSet rs = statement.executeQuery()) { List<UUID> ids = new ArrayList<>(); while (rs.next()) ids.add(rs.getObject(1, UUID.class)); return ids; }
        }
    }

    private TrackReport processTrack(Connection connection, UUID trackId, LocalDate through, int batch) throws Exception {
        boolean oldAutoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
        try {
            Track track = lockTrack(connection, trackId);
            if (track == null) { connection.rollback(); return new TrackReport(0, 0); }
            List<CandidateRow> rows = candidates(connection, track, through, batch);
            Set<String> occupied = occupiedSymbols(connection, track.portfolioId());
            BigDecimal pendingReserve = pendingReserve(connection, track.portfolioId());
            BigDecimal availableCash = track.cash().subtract(pendingReserve).max(BigDecimal.ZERO);
            BigDecimal nav = currentNav(connection, track).max(track.cash());
            List<RankedBuyAllocator.Candidate> candidates = rows.stream().map(row -> new RankedBuyAllocator.Candidate(
                row.signalId().toString(), row.symbol(), row.signalStrength(), row.priorLiquidity(), estimatedUnitCost(row)
            )).toList();
            RankedBuyAllocator.Result allocation = allocator.allocate(new RankedBuyAllocator.Input(
                candidates, nav, availableCash, occupied, track.maxOpenPositions(), track.maxWeight()
            ));
            int orders = 0;
            for (RankedBuyAllocator.Selection selected : allocation.selected()) {
                CandidateRow row = rows.stream().filter(value -> value.signalId().toString().equals(selected.candidate().signalId())).findFirst().orElseThrow();
                if (insertOrder(connection, track, row, selected.quantity())) orders++;
            }
            connection.commit(); return new TrackReport(rows.size(), orders);
        } catch (Exception error) { connection.rollback(); throw error; }
        finally { connection.setAutoCommit(oldAutoCommit); }
    }

    private Track lockTrack(Connection connection, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT rt.id,rt.user_id,rt.portfolio_id,rt.strategy_version_id,rt.universe_version_id,rt.sell_rule_version_id,rt.fill_model_version_id,rt.cost_model_version_id,
                   rt.max_position_weight,rt.max_open_positions,p.cash_balance
              FROM ranking_tracks rt JOIN portfolios p ON p.id=rt.portfolio_id
             WHERE rt.id=? AND rt.status='ACTIVE' AND p.kind='RANKED_PAPER' FOR UPDATE OF rt,p
            """)) {
            statement.setObject(1,id); try(ResultSet rs=statement.executeQuery()){return rs.next()?new Track(
                rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class),rs.getObject("portfolio_id",UUID.class),
                rs.getObject("strategy_version_id",UUID.class),rs.getObject("universe_version_id",UUID.class),
                rs.getObject("sell_rule_version_id",UUID.class),rs.getObject("fill_model_version_id",UUID.class),
                rs.getObject("cost_model_version_id",UUID.class),rs.getBigDecimal("max_position_weight"),
                rs.getInt("max_open_positions"),rs.getBigDecimal("cash_balance")):null;}
        }
    }

    private List<CandidateRow> candidates(Connection connection, Track track, LocalDate through, int batch) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT s.id signal_id,i.id instrument_id,i.symbol,s.signal_strength,s.prior_liquidity_score,c.close,
                   ms.id session_id,ms.session_date,ms.open_at,pfm.slippage_buy_bps,pfm.spread_bps,cm.buy_fee_rate
              FROM signals s JOIN instruments i ON i.id=s.instrument_id
              JOIN candles c ON c.instrument_id=s.instrument_id AND c.timeframe='D1' AND c.close_at=s.candle_close_at
              JOIN paper_fill_model_versions pfm ON pfm.id=? JOIN cost_model_versions cm ON cm.id=?
              JOIN LATERAL (SELECT m.id,m.session_date,m.open_at FROM market_sessions m WHERE m.market=i.market AND m.is_trading_day
                AND m.session_date>s.candle_close_at::date ORDER BY m.session_date LIMIT 1) ms ON true
             WHERE s.strategy_version_id=? AND s.signal_type='BUY_CONDITION' AND NOT s.data_is_stale
               AND s.candle_close_at::date<=? AND NOT EXISTS(SELECT 1 FROM paper_orders po WHERE po.source_signal_id=s.id)
             ORDER BY s.candle_close_at,s.signal_strength DESC NULLS LAST,s.prior_liquidity_score DESC NULLS LAST,i.symbol
             LIMIT ?
            """)) {
            statement.setObject(1,track.fillModelId()); statement.setObject(2,track.costModelId());
            statement.setObject(3,track.strategyVersionId()); statement.setObject(4,through); statement.setInt(5,batch);
            try(ResultSet rs=statement.executeQuery()){List<CandidateRow> result=new ArrayList<>();while(rs.next())result.add(new CandidateRow(
                rs.getObject("signal_id",UUID.class),rs.getObject("instrument_id",UUID.class),rs.getString("symbol"),
                rs.getBigDecimal("signal_strength"),rs.getBigDecimal("prior_liquidity_score"),rs.getBigDecimal("close"),
                rs.getObject("session_id",UUID.class),rs.getObject("session_date",LocalDate.class),rs.getObject("open_at",OffsetDateTime.class),
                rs.getBigDecimal("slippage_buy_bps"),rs.getBigDecimal("spread_bps"),rs.getBigDecimal("buy_fee_rate")));return result;}
        }
    }

    private Set<String> occupiedSymbols(Connection c,UUID portfolioId)throws Exception{
        try(PreparedStatement s=c.prepareStatement("""
            SELECT i.symbol FROM positions p JOIN instruments i ON i.id=p.instrument_id WHERE p.portfolio_id=? AND p.status IN ('OPEN','EXIT_PENDING','PARTIALLY_CLOSED')
            UNION SELECT i.symbol FROM paper_orders po JOIN instruments i ON i.id=po.instrument_id WHERE po.portfolio_id=? AND po.side='BUY' AND po.status='PENDING'
            """)){s.setObject(1,portfolioId);s.setObject(2,portfolioId);try(ResultSet r=s.executeQuery()){Set<String>x=new HashSet<>();while(r.next())x.add(r.getString(1));return x;}}
    }
    private BigDecimal pendingReserve(Connection c,UUID portfolioId)throws Exception{
        try(PreparedStatement s=c.prepareStatement("""
            SELECT COALESCE(sum(po.quantity*c.close*(1+(pf.slippage_buy_bps+pf.spread_bps/2)/10000+cm.buy_fee_rate)),0)
              FROM paper_orders po JOIN signals sg ON sg.id=po.source_signal_id
              JOIN candles c ON c.instrument_id=sg.instrument_id AND c.close_at=sg.candle_close_at AND c.timeframe='D1'
              JOIN paper_fill_model_versions pf ON pf.id=po.fill_model_version_id JOIN cost_model_versions cm ON cm.id=po.cost_model_version_id
             WHERE po.portfolio_id=? AND po.side='BUY' AND po.status='PENDING'
            """)){s.setObject(1,portfolioId);try(ResultSet r=s.executeQuery()){r.next();return r.getBigDecimal(1);}}
    }
    private BigDecimal currentNav(Connection c,Track track)throws Exception{
        try(PreparedStatement s=c.prepareStatement("""
            SELECT p.cash_balance+COALESCE(sum(pos.quantity*lc.close),0) nav FROM portfolios p
              LEFT JOIN positions pos ON pos.portfolio_id=p.id AND pos.status IN ('OPEN','EXIT_PENDING','PARTIALLY_CLOSED')
                AND EXISTS(SELECT 1 FROM position_executions pe WHERE pe.position_id=pos.id)
              LEFT JOIN LATERAL (SELECT close FROM candles WHERE instrument_id=pos.instrument_id AND timeframe='D1' AND is_final AND NOT is_stale ORDER BY close_at DESC LIMIT 1) lc ON true
             WHERE p.id=? GROUP BY p.cash_balance
            """)){s.setObject(1,track.portfolioId());try(ResultSet r=s.executeQuery()){return r.next()?r.getBigDecimal(1):track.cash();}}
    }
    private BigDecimal estimatedUnitCost(CandidateRow r){BigDecimal bps=r.buySlippageBps().add(r.spreadBps().divide(BigDecimal.valueOf(2)));return r.close().multiply(BigDecimal.ONE.add(bps.divide(BigDecimal.valueOf(10000),12,RoundingMode.UP)).add(r.buyFeeRate())).setScale(6,RoundingMode.UP);}
    private boolean insertOrder(Connection c,Track t,CandidateRow r,long quantity)throws Exception{
        UUID positionId;
        try(PreparedStatement s=c.prepareStatement("""
            INSERT INTO positions(user_id,portfolio_id,portfolio_kind,instrument_id,status,quantity,average_cost,realized_pnl,
              opened_at,first_execution_at,strategy_version_id,buy_signal_id,universe_version_id,sell_rule_version_id)
            VALUES (?,?,'RANKED_PAPER',?,'OPEN',?,0,0,?,?,?,?,?,?) RETURNING id
            """)){
            s.setObject(1,t.userId());s.setObject(2,t.portfolioId());s.setObject(3,r.instrumentId());s.setLong(4,quantity);
            s.setObject(5,r.openAt());s.setObject(6,r.openAt());s.setObject(7,t.strategyVersionId());s.setObject(8,r.signalId());
            s.setObject(9,t.universeVersionId());s.setObject(10,t.sellRuleVersionId());
            try(ResultSet x=s.executeQuery()){x.next();positionId=x.getObject(1,UUID.class);}
        }
        UUID orderId;
        try(PreparedStatement s=c.prepareStatement("""
            INSERT INTO paper_orders(user_id,portfolio_id,portfolio_kind,position_id,instrument_id,side,quantity,status,scheduled_market_session_id,
              source_signal_id,fill_model_version_id,cost_model_version_id,can_user_cancel,idempotency_key)
            VALUES (?,?,'RANKED_PAPER',?,?,'BUY',?,'PENDING',?,?,?,?,false,?)
            ON CONFLICT(portfolio_id,idempotency_key) DO NOTHING RETURNING id
            """)){s.setObject(1,t.userId());s.setObject(2,t.portfolioId());s.setObject(3,positionId);s.setObject(4,r.instrumentId());s.setLong(5,quantity);s.setObject(6,r.sessionId());s.setObject(7,r.signalId());s.setObject(8,t.fillModelId());s.setObject(9,t.costModelId());s.setString(10,"ranked-buy:"+t.id()+":"+r.signalId());try(ResultSet x=s.executeQuery()){if(!x.next())throw new IllegalStateException("Ranked BUY idempotency conflict after reserved position creation");orderId=x.getObject(1,UUID.class);}}
        try(PreparedStatement s=c.prepareStatement("""
            INSERT INTO ranking_track_events(user_id,ranking_track_id,event_type,payload,idempotency_key)
            VALUES (?,?,'ORDER_CREATED',jsonb_build_object('paperOrderId',?::text,'signalId',?::text,'symbol',?,'quantity',?,'sessionDate',?::text),?) ON CONFLICT DO NOTHING
            """)){s.setObject(1,t.userId());s.setObject(2,t.id());s.setObject(3,orderId);s.setObject(4,r.signalId());s.setString(5,r.symbol());s.setLong(6,quantity);s.setObject(7,r.sessionDate());s.setString(8,"order-created:"+orderId);s.executeUpdate();}
        return true;
    }
    private record Track(UUID id,UUID userId,UUID portfolioId,UUID strategyVersionId,UUID universeVersionId,UUID sellRuleVersionId,UUID fillModelId,UUID costModelId,BigDecimal maxWeight,int maxOpenPositions,BigDecimal cash){}
    private record CandidateRow(UUID signalId,UUID instrumentId,String symbol,BigDecimal signalStrength,BigDecimal priorLiquidity,BigDecimal close,UUID sessionId,LocalDate sessionDate,OffsetDateTime openAt,BigDecimal buySlippageBps,BigDecimal spreadBps,BigDecimal buyFeeRate){}
    private record TrackReport(int candidates,int orders){}
    public record Report(int candidates,int ordersCreated,String source){}
}
