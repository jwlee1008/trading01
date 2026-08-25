package com.signallab.api.domain.portfolio.service;

import com.signallab.api.domain.execution.dto.ExecutionResponse;
import com.signallab.api.domain.portfolio.dto.PortfolioResponse;
import com.signallab.api.domain.portfolio.dto.PositionResponse;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PortfolioQueryService {

    private final JdbcTemplate jdbcTemplate;

    public PortfolioQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PortfolioResponse> portfoliosFor(UUID userId) {
        List<PortfolioRow> portfolios = jdbcTemplate.query(
            """
            SELECT id, user_id, name, kind, cash_balance
            FROM portfolios WHERE user_id = ? AND archived_at IS NULL ORDER BY created_at ASC
            """,
            (rs, rowNum) -> new PortfolioRow(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("user_id")), rs.getString("name"),
                rs.getString("kind"), rs.getBigDecimal("cash_balance")
            ), userId
        );
        List<PositionResponse> positions = positionsFor(userId);
        Map<UUID, List<PositionResponse>> byPortfolio = positions.stream().collect(Collectors.groupingBy(PositionResponse::portfolioId));
        return portfolios.stream().map(portfolio -> {
            List<PositionResponse> owned = byPortfolio.getOrDefault(portfolio.id(), List.of());
            BigDecimal marketValue = owned.stream()
                .filter(position -> !"CLOSED".equals(position.status()) && !"ARCHIVED".equals(position.status()))
                .map(position -> new BigDecimal(position.currentPrice()).multiply(BigDecimal.valueOf(position.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new PortfolioResponse(
                portfolio.id(), portfolio.userId(), portfolio.name(), portfolio.kind(), decimal(portfolio.cash()),
                decimal(portfolio.cash().add(marketValue)), owned
            );
        }).toList();
    }

    public List<PositionResponse> positionsFor(UUID userId) {
        List<PositionRow> positions = jdbcTemplate.query(
            """
            SELECT p.id, p.portfolio_id, i.symbol, i.name_ko, p.status, p.quantity, p.average_cost,
                   latest.close AS current_market_price, p.highest_completed_close, p.opened_at, p.realized_pnl,
                   p.buy_signal_id, p.sell_rule_version_id
            FROM positions p JOIN instruments i ON i.id = p.instrument_id
            LEFT JOIN LATERAL (
              SELECT c.close FROM candles c
              WHERE c.instrument_id = p.instrument_id AND c.is_final AND NOT c.is_stale
              ORDER BY c.close_at DESC LIMIT 1
            ) latest ON TRUE
            WHERE p.user_id = ? ORDER BY p.opened_at ASC
            """,
            (rs, rowNum) -> new PositionRow(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("portfolio_id")),
                rs.getString("symbol"), rs.getString("name_ko"), rs.getString("status"), rs.getLong("quantity"),
                rs.getBigDecimal("average_cost"), rs.getBigDecimal("current_market_price"), rs.getBigDecimal("highest_completed_close"),
                rs.getTimestamp("opened_at").toInstant().atOffset(ZoneOffset.UTC), rs.getBigDecimal("realized_pnl"),
                nullableUuid(rs.getString("buy_signal_id")), nullableUuid(rs.getString("sell_rule_version_id"))
            ), userId
        );
        Map<UUID, List<ExecutionResponse>> executions = jdbcTemplate.query(
            """
            SELECT e.id, e.portfolio_id, e.position_id, i.symbol, e.side, e.unit_price, e.quantity,
                   e.fee, e.tax, e.executed_at, e.note, e.source_signal_id, e.idempotency_key, e.reverses_execution_id
            FROM position_executions e JOIN instruments i ON i.id = e.instrument_id
            WHERE e.user_id = ?
            ORDER BY e.executed_at ASC, e.recorded_at ASC, e.id ASC
            """,
            (rs, rowNum) -> new ExecutionResponse(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("portfolio_id")), UUID.fromString(rs.getString("position_id")),
                rs.getString("symbol"), rs.getString("side"), decimal(rs.getBigDecimal("unit_price")), rs.getLong("quantity"),
                decimal(rs.getBigDecimal("fee")), decimal(rs.getBigDecimal("tax")), rs.getTimestamp("executed_at").toInstant().atOffset(ZoneOffset.UTC),
                rs.getString("note"), nullableUuid(rs.getString("source_signal_id")), rs.getString("idempotency_key"), nullableUuid(rs.getString("reverses_execution_id"))
            ), userId
        ).stream().collect(Collectors.groupingBy(ExecutionResponse::positionId));
        return positions.stream().map(position -> {
            BigDecimal highestClose = position.highestClose() == null ? position.averagePrice() : position.highestClose();
            return new PositionResponse(
                position.id(), position.portfolioId(), position.symbol(), position.name(), position.status(), position.quantity(),
                decimal(position.averagePrice()), decimal(position.currentMarketPrice() == null ? position.averagePrice() : position.currentMarketPrice()),
                position.currentMarketPrice() != null, decimal(highestClose), position.openedAt(),
                decimal(position.realizedPnl()), position.linkedSignalId(), position.sellRuleVersionId(),
                executions.getOrDefault(position.id(), List.of())
            );
        }).toList();
    }

    private static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static String decimal(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }

    private record PortfolioRow(UUID id, UUID userId, String name, String kind, BigDecimal cash) {}
    private record PositionRow(UUID id, UUID portfolioId, String symbol, String name, String status, long quantity,
                               BigDecimal averagePrice, BigDecimal currentMarketPrice, BigDecimal highestClose, java.time.OffsetDateTime openedAt,
                               BigDecimal realizedPnl, UUID linkedSignalId, UUID sellRuleVersionId) {}
}
