package com.signallab.api.domain.execution.service;

import com.signallab.api.domain.execution.dto.ExecutionResponse;
import com.signallab.api.domain.execution.dto.ManualExecutionRequest;
import com.signallab.api.domain.execution.dto.ManualExecutionResponse;
import com.signallab.api.domain.portfolio.dto.PositionResponse;
import com.signallab.api.domain.portfolio.service.PortfolioQueryService;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManualExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final PortfolioQueryService portfolioQueryService;

    public ManualExecutionService(JdbcTemplate jdbcTemplate, PortfolioQueryService portfolioQueryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.portfolioQueryService = portfolioQueryService;
    }

    @Transactional
    public ManualExecutionResponse register(UUID userId, UUID portfolioId, ManualExecutionRequest request) {
        BigDecimal price = new BigDecimal(request.price());
        if (price.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "가격은 0보다 커야 합니다.");
        PortfolioRow portfolio = portfolio(userId, portfolioId);
        if (!"MANUAL_LIVE".equals(portfolio.kind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수동 체결은 실제 수동 보유 원장에만 등록할 수 있습니다.");
        }
        ExecutionRow prior = priorExecution(portfolioId, request.idempotencyKey());
        if (prior != null) {
            if (!prior.side().equals(request.side()) || prior.quantity() != request.quantity() || prior.price().compareTo(price) != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "멱등 키가 다른 요청 본문에 이미 사용되었습니다.");
            }
            return new ManualExecutionResponse(toResponse(prior, request.symbol()), requirePosition(userId, prior.positionId()), true, null);
        }

        UUID instrumentId = instrumentId(request.symbol());
        PositionRow position = request.positionId() == null || request.positionId().isBlank()
            ? openPosition(userId, portfolioId, instrumentId)
            : positionById(userId, parseUuid(request.positionId(), "포지션을 찾을 수 없습니다."));
        if (request.positionId() != null && !request.positionId().isBlank() && position == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "포지션을 찾을 수 없습니다.");
        }
        if (position != null && (!position.portfolioId().equals(portfolioId) || !position.instrumentId().equals(instrumentId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "포지션을 찾을 수 없습니다.");
        }
        if ("SELL".equals(request.side()) && (position == null || position.quantity() < request.quantity())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "가용 보유량을 초과한 매도입니다.");
        }

        UUID signalId = optionalUuid(request.signalId());
        if ("BUY".equals(request.side())) {
            position = position == null
                ? createPosition(userId, portfolioId, instrumentId, request.quantity(), price, request.executedAt(), signalId)
                : addBuy(position, request.quantity(), price);
        } else {
            position = applySell(position, request.quantity(), price, request.executedAt());
        }

        ExecutionRow execution = jdbcTemplate.queryForObject(
            """
            INSERT INTO position_executions
              (user_id, portfolio_id, position_id, portfolio_kind, instrument_id, side, executed_at, unit_price, quantity, note, source_signal_id, idempotency_key)
            VALUES (?, ?, ?, 'MANUAL_LIVE', ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, portfolio_id, position_id, side, unit_price, quantity, fee, tax, executed_at, source_signal_id, idempotency_key, reverses_execution_id
            """,
            (rs, rowNum) -> executionRow(rs), userId, portfolioId, position.id(), instrumentId, request.side(),
            java.sql.Timestamp.from(request.executedAt().toInstant()), price, request.quantity(), request.memo() == null ? "" : request.memo(), signalId, request.idempotencyKey()
        );
        return new ManualExecutionResponse(toResponse(execution, request.symbol()), requirePosition(userId, position.id()), false, null);
    }

    private PortfolioRow portfolio(UUID userId, UUID portfolioId) {
        List<PortfolioRow> rows = jdbcTemplate.query(
            "SELECT id, kind FROM portfolios WHERE id = ? AND user_id = ?",
            (rs, rowNum) -> new PortfolioRow(UUID.fromString(rs.getString("id")), rs.getString("kind")), portfolioId, userId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "포트폴리오를 찾을 수 없습니다.");
        return rows.getFirst();
    }

    private UUID instrumentId(String symbol) {
        List<UUID> rows = jdbcTemplate.query("SELECT id FROM instruments WHERE symbol = ?", (rs, rowNum) -> UUID.fromString(rs.getString("id")), symbol);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "종목 master에 없는 코드입니다. 키움 종목정보 import 뒤 다시 시도하세요.");
        return rows.getFirst();
    }

    private PositionRow openPosition(UUID userId, UUID portfolioId, UUID instrumentId) {
        return jdbcTemplate.query(
            """
            SELECT id, portfolio_id, instrument_id, quantity, average_cost, realized_pnl
            FROM positions WHERE user_id = ? AND portfolio_id = ? AND instrument_id = ?
              AND status NOT IN ('CLOSED', 'ARCHIVED') LIMIT 1
            """, rs -> rs.next() ? positionRow(rs) : null, userId, portfolioId, instrumentId
        );
    }

    private PositionRow positionById(UUID userId, UUID positionId) {
        return jdbcTemplate.query(
            "SELECT id, portfolio_id, instrument_id, quantity, average_cost, realized_pnl FROM positions WHERE id = ? AND user_id = ?",
            rs -> rs.next() ? positionRow(rs) : null, positionId, userId
        );
    }

    private PositionRow createPosition(UUID userId, UUID portfolioId, UUID instrumentId, long quantity, BigDecimal price, java.time.OffsetDateTime executedAt, UUID signalId) {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO positions
              (user_id, portfolio_id, portfolio_kind, instrument_id, status, quantity, average_cost, highest_completed_close, opened_at, first_execution_at, buy_signal_id)
            VALUES (?, ?, 'MANUAL_LIVE', ?, 'OPEN', ?, ?, ?, ?, ?, ?)
            RETURNING id, portfolio_id, instrument_id, quantity, average_cost, realized_pnl
            """, (rs, rowNum) -> positionRow(rs), userId, portfolioId, instrumentId, quantity, price, price,
            java.sql.Timestamp.from(executedAt.toInstant()), java.sql.Timestamp.from(executedAt.toInstant()), signalId
        );
    }

    private PositionRow addBuy(PositionRow position, long quantity, BigDecimal price) {
        long nextQuantity = Math.addExact(position.quantity(), quantity);
        BigDecimal average = position.averageCost().multiply(BigDecimal.valueOf(position.quantity()))
            .add(price.multiply(BigDecimal.valueOf(quantity))).divide(BigDecimal.valueOf(nextQuantity), 6, java.math.RoundingMode.HALF_UP);
        jdbcTemplate.update("UPDATE positions SET quantity = ?, average_cost = ?, status = 'OPEN', updated_at = NOW() WHERE id = ?", nextQuantity, average, position.id());
        return new PositionRow(position.id(), position.portfolioId(), position.instrumentId(), nextQuantity, average, position.realizedPnl());
    }

    private PositionRow applySell(PositionRow position, long quantity, BigDecimal price, java.time.OffsetDateTime executedAt) {
        long nextQuantity = position.quantity() - quantity;
        BigDecimal realized = position.realizedPnl().add(price.subtract(position.averageCost()).multiply(BigDecimal.valueOf(quantity)));
        jdbcTemplate.update(
            "UPDATE positions SET quantity = ?, realized_pnl = ?, status = ?, closed_at = ?, updated_at = NOW() WHERE id = ?",
            nextQuantity, realized, nextQuantity == 0 ? "CLOSED" : "PARTIALLY_CLOSED",
            nextQuantity == 0 ? java.sql.Timestamp.from(executedAt.toInstant()) : null, position.id()
        );
        return new PositionRow(position.id(), position.portfolioId(), position.instrumentId(), nextQuantity, position.averageCost(), realized);
    }

    private ExecutionRow priorExecution(UUID portfolioId, String key) {
        List<ExecutionRow> rows = jdbcTemplate.query(
            """
            SELECT id, portfolio_id, position_id, side, unit_price, quantity, fee, tax, executed_at, source_signal_id, idempotency_key, reverses_execution_id
            FROM position_executions WHERE portfolio_id = ? AND idempotency_key = ?
            """, (rs, rowNum) -> executionRow(rs), portfolioId, key
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PositionResponse requirePosition(UUID userId, UUID positionId) {
        return portfolioQueryService.positionsFor(userId).stream().filter(position -> position.id().equals(positionId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "포지션을 찾을 수 없습니다."));
    }

    private static PositionRow positionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PositionRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("portfolio_id")),
            UUID.fromString(rs.getString("instrument_id")), rs.getLong("quantity"), rs.getBigDecimal("average_cost"), rs.getBigDecimal("realized_pnl"));
    }

    private static ExecutionRow executionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExecutionRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("portfolio_id")), UUID.fromString(rs.getString("position_id")),
            rs.getString("side"), rs.getBigDecimal("unit_price"), rs.getLong("quantity"), rs.getBigDecimal("fee"), rs.getBigDecimal("tax"),
            rs.getTimestamp("executed_at").toInstant().atOffset(ZoneOffset.UTC), optionalUuid(rs.getString("source_signal_id")), rs.getString("idempotency_key"), optionalUuid(rs.getString("reverses_execution_id")));
    }

    private static ExecutionResponse toResponse(ExecutionRow execution, String symbol) {
        return new ExecutionResponse(execution.id(), execution.portfolioId(), execution.positionId(), symbol, execution.side(), decimal(execution.price()), execution.quantity(),
            decimal(execution.fee()), decimal(execution.tax()), execution.executedAt(), execution.signalId(), execution.idempotencyKey(), execution.correctionOf());
    }

    private static UUID optionalUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private static UUID parseUuid(String value, String message) {
        UUID uuid = optionalUuid(value);
        if (uuid == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return uuid;
    }

    private static String decimal(BigDecimal value) { return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString(); }

    private record PortfolioRow(UUID id, String kind) {}
    private record PositionRow(UUID id, UUID portfolioId, UUID instrumentId, long quantity, BigDecimal averageCost, BigDecimal realizedPnl) {}
    private record ExecutionRow(UUID id, UUID portfolioId, UUID positionId, String side, BigDecimal price, long quantity, BigDecimal fee, BigDecimal tax, java.time.OffsetDateTime executedAt, UUID signalId, String idempotencyKey, UUID correctionOf) {}
}
