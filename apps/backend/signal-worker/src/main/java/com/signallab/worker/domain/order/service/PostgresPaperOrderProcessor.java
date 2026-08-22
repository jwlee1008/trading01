package com.signallab.worker.domain.order.service;

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
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** Applies due D+1 paper fills from finalized PostgreSQL candle opens. */
@Service
public class PostgresPaperOrderProcessor {
    private final DataSource dataSource;

    public PostgresPaperOrderProcessor(DataSource dataSource) { this.dataSource = dataSource; }

    public Report process(WorkerProperties properties) {
        if (!properties.isEnabled() || !properties.isPaperOrdersEnabled()) return new Report(0, 0, "disabled");
        LocalDate through = properties.getExpectedThrough() == null || properties.getExpectedThrough().isBlank()
            ? OffsetDateTime.now(ZoneOffset.ofHours(9)).toLocalDate() : LocalDate.parse(properties.getExpectedThrough());
        int batch = properties.getPaperOrderBatchSize();
        if (batch < 1 || batch > 1_000) throw new IllegalArgumentException("paperOrderBatchSize must be between 1 and 1000");
        try (Connection connection = dataSource.getConnection()) {
            List<Order> orders = loadDue(connection, through, batch);
            int filled = 0;
            for (Order order : orders) if (order.fillable()) {
                try {
                    applyFill(connection, order);
                    filled++;
                } catch (Exception ignored) {
                    // The DB function is authoritative for stale concurrent claims and ledger invariants.
                }
            }
            return new Report(orders.size(), filled, "postgres");
        } catch (Exception error) {
            throw new IllegalStateException("Spring paper order processing failed", error);
        }
    }

    private List<Order> loadDue(Connection connection, LocalDate through, int limit) throws Exception {
        String sql = """
            SELECT po.id, po.side, po.quantity, po.portfolio_kind, ms.session_date, ms.open_at,
                   p.cash_balance, pos.quantity AS available_quantity,
                   pfm.slippage_buy_bps, pfm.slippage_sell_bps, pfm.spread_bps,
                   cm.buy_fee_rate, cm.sell_fee_rate, cm.sell_tax_rate,
                   c.open AS official_open, c.volume, c.is_final, c.is_stale
              FROM public.paper_orders po
              JOIN public.portfolios p ON p.id = po.portfolio_id
              LEFT JOIN public.positions pos ON pos.id = po.position_id
              JOIN public.market_sessions ms ON ms.id = po.scheduled_market_session_id
              JOIN public.paper_fill_model_versions pfm ON pfm.id = po.fill_model_version_id
              JOIN public.cost_model_versions cm ON cm.id = po.cost_model_version_id
              LEFT JOIN public.candles c ON c.instrument_id = po.instrument_id AND c.timeframe = 'D1' AND c.session_date = ms.session_date
             WHERE po.status = 'PENDING' AND ms.is_trading_day AND ms.session_date <= ?
             ORDER BY ms.session_date, po.id LIMIT ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, through); statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<Order> result = new ArrayList<>();
                while (rows.next()) result.add(new Order(rows.getObject("id", UUID.class), rows.getString("side"), rows.getLong("quantity"),
                    rows.getString("portfolio_kind"), rows.getObject("open_at", OffsetDateTime.class), rows.getBigDecimal("cash_balance"),
                    rows.getObject("available_quantity") == null ? 0L : rows.getLong("available_quantity"),
                    rows.getBigDecimal("slippage_buy_bps"), rows.getBigDecimal("slippage_sell_bps"), rows.getBigDecimal("spread_bps"),
                    rows.getBigDecimal("buy_fee_rate"), rows.getBigDecimal("sell_fee_rate"), rows.getBigDecimal("sell_tax_rate"),
                    rows.getBigDecimal("official_open"), rows.getBigDecimal("volume"), rows.getBoolean("is_final"), rows.getBoolean("is_stale")));
                return result;
            }
        }
    }

    private void applyFill(Connection connection, Order order) throws Exception {
        BigDecimal price = fillPrice(order);
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM public.apply_paper_fill(CAST(? AS uuid), ?, ?, ?)")) {
            statement.setObject(1, order.id()); statement.setBigDecimal(2, price); statement.setObject(3, order.openAt());
            statement.setString(4, "paper-fill:" + order.id());
            statement.executeQuery();
        }
    }

    private BigDecimal fillPrice(Order order) {
        BigDecimal bps = ("BUY".equals(order.side()) ? order.buySlippageBps() : order.sellSlippageBps())
            .add(order.spreadBps().divide(BigDecimal.valueOf(2)));
        BigDecimal multiplier = BigDecimal.ONE.add(bps.divide(BigDecimal.valueOf(10_000), 12, RoundingMode.HALF_UP)
            .multiply("BUY".equals(order.side()) ? BigDecimal.ONE : BigDecimal.ONE.negate()));
        BigDecimal adjusted = order.officialOpen().multiply(multiplier);
        BigDecimal tick = adjusted.compareTo(BigDecimal.valueOf(2_000)) < 0 ? BigDecimal.ONE
            : adjusted.compareTo(BigDecimal.valueOf(5_000)) < 0 ? BigDecimal.valueOf(5)
            : adjusted.compareTo(BigDecimal.valueOf(20_000)) < 0 ? BigDecimal.TEN
            : adjusted.compareTo(BigDecimal.valueOf(50_000)) < 0 ? BigDecimal.valueOf(50)
            : adjusted.compareTo(BigDecimal.valueOf(200_000)) < 0 ? BigDecimal.valueOf(100)
            : adjusted.compareTo(BigDecimal.valueOf(500_000)) < 0 ? BigDecimal.valueOf(500) : BigDecimal.valueOf(1_000);
        return adjusted.divide(tick, 0, "BUY".equals(order.side()) ? RoundingMode.CEILING : RoundingMode.FLOOR).multiply(tick);
    }

    private record Order(UUID id, String side, long quantity, String portfolioKind, OffsetDateTime openAt, BigDecimal cash, long availableQuantity,
                         BigDecimal buySlippageBps, BigDecimal sellSlippageBps, BigDecimal spreadBps, BigDecimal buyFeeRate, BigDecimal sellFeeRate,
                         BigDecimal sellTaxRate, BigDecimal officialOpen, BigDecimal volume, boolean isFinal, boolean isStale) {
        boolean fillable() { return quantity > 0 && officialOpen != null && officialOpen.signum() > 0 && volume != null && volume.signum() > 0 && isFinal && !isStale
            && (!"SELL".equals(side) || quantity <= availableQuantity) && (!"BUY".equals(side) || cash.signum() >= 0); }
    }
    public record Report(int due, int filled, String source) {}
}
