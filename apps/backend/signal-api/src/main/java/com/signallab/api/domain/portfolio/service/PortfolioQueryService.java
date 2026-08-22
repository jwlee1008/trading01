package com.signallab.api.domain.portfolio.service;

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
        return jdbcTemplate.query(
            """
            SELECT p.id, p.portfolio_id, i.symbol, i.name_ko, p.status, p.quantity, p.average_cost,
                   p.highest_completed_close, p.opened_at, p.realized_pnl, p.buy_signal_id, p.sell_rule_version_id
            FROM positions p JOIN instruments i ON i.id = p.instrument_id
            WHERE p.user_id = ? ORDER BY p.opened_at ASC
            """,
            (rs, rowNum) -> {
                BigDecimal averagePrice = rs.getBigDecimal("average_cost");
                BigDecimal highestClose = rs.getBigDecimal("highest_completed_close");
                return new PositionResponse(
                    UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("portfolio_id")),
                    rs.getString("symbol"), rs.getString("name_ko"), rs.getString("status"), rs.getLong("quantity"),
                    decimal(averagePrice), decimal(averagePrice), decimal(highestClose == null ? averagePrice : highestClose),
                    rs.getTimestamp("opened_at").toInstant().atOffset(ZoneOffset.UTC), decimal(rs.getBigDecimal("realized_pnl")),
                    nullableUuid(rs.getString("buy_signal_id")), nullableUuid(rs.getString("sell_rule_version_id"))
                );
            }, userId
        );
    }

    private static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static String decimal(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }

    private record PortfolioRow(UUID id, UUID userId, String name, String kind, BigDecimal cash) {}
}
