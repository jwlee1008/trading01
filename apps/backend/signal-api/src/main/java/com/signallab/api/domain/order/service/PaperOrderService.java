package com.signallab.api.domain.order.service;

import com.signallab.api.domain.order.dto.PaperOrderRequest;
import com.signallab.api.domain.order.dto.PaperOrderResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaperOrderService {

    private final JdbcTemplate jdbcTemplate;

    public PaperOrderService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PaperOrderResponse> findByUserId(UUID userId) {
        return jdbcTemplate.query(
            """
            SELECT po.id, po.portfolio_id, po.position_id, i.symbol, po.side, po.quantity,
                   COALESCE(po.source_signal_id, po.source_position_signal_id) AS signal_id, po.status,
                   po.submitted_at, ms.session_date, cm.code AS cost_code, po.idempotency_key, po.rejection_reason
            FROM paper_orders po
            JOIN instruments i ON i.id = po.instrument_id
            JOIN market_sessions ms ON ms.id = po.scheduled_market_session_id
            JOIN cost_model_versions cm ON cm.id = po.cost_model_version_id
            WHERE po.user_id = ? ORDER BY po.submitted_at DESC
            """, (rs, rowNum) -> response(rs), userId
        );
    }

    @Transactional
    public OrderResult place(UUID userId, PaperOrderRequest request) {
        UUID portfolioId = uuid(request.portfolioId(), "포트폴리오를 찾을 수 없습니다.");
        PortfolioRow portfolio = portfolio(userId, portfolioId);
        if ("MANUAL_LIVE".equals(portfolio.kind())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이퍼 주문은 페이퍼 원장에만 만들 수 있습니다.");
        if ("RANKED_PAPER".equals(portfolio.kind())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공식 랭킹 주문은 잠긴 전략 신호에서만 자동 생성됩니다.");
        UUID instrumentId = instrumentId(request.symbol());
        PaperOrderRow prior = prior(portfolioId, request.idempotencyKey());
        if (prior != null) {
            boolean differs = !prior.side().equals(request.side()) || prior.quantity() != request.quantity() || !prior.instrumentId().equals(instrumentId)
                || ("SELL".equals(request.side()) && !sameUuid(prior.positionId(), request.positionId()))
                || ("BUY".equals(request.side()) && request.positionId() != null && !request.positionId().isBlank() && !sameUuid(prior.positionId(), request.positionId()));
            if (differs) throw new ResponseStatusException(HttpStatus.CONFLICT, "멱등 키가 다른 요청 본문에 이미 사용되었습니다.");
            return new OrderResult(details(prior.id()), true);
        }

        UUID positionId;
        if ("SELL".equals(request.side())) {
            if (request.positionId() == null || request.positionId().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "매도 포지션 ID가 필요합니다.");
            PositionRow position = position(userId, uuid(request.positionId(), "포지션을 찾을 수 없습니다."));
            if (position == null || !position.portfolioId().equals(portfolioId) || !position.instrumentId().equals(instrumentId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "포지션을 찾을 수 없습니다.");
            }
            Long reserved = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM paper_orders WHERE position_id = ? AND side = 'SELL' AND status = 'PENDING'",
                Long.class, position.id()
            );
            if (request.quantity() > position.quantity() - (reserved == null ? 0 : reserved)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "예약 수량을 포함한 가용 보유량을 초과했습니다.");
            }
            positionId = position.id();
        } else {
            PositionRow position = request.positionId() == null || request.positionId().isBlank()
                ? openPosition(userId, portfolioId, instrumentId)
                : position(userId, uuid(request.positionId(), "포지션을 찾을 수 없습니다."));
            if (position != null && (!position.portfolioId().equals(portfolioId) || !position.instrumentId().equals(instrumentId))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "포지션을 찾을 수 없습니다.");
            }
            if (position == null) position = reservePosition(userId, portfolioId, instrumentId, request.quantity(), optionalUuid(request.signalId()));
            else if (!hasExecution(position.id())) {
                if (hasPendingOrder(position.id())) throw new ResponseStatusException(HttpStatus.CONFLICT, "첫 매수 주문 체결 전에는 같은 종목 주문을 추가할 수 없습니다.");
                jdbcTemplate.update("UPDATE positions SET quantity = ?, average_cost = 0, realized_pnl = 0, status = 'OPEN', updated_at = NOW() WHERE id = ?", request.quantity(), position.id());
            }
            positionId = position.id();
        }

        UUID sessionId = nextTradingSession();
        UUID fillModelId = latestModelId("paper_fill_model_versions");
        UUID costModelId = latestModelId("cost_model_versions");
        UUID orderId = jdbcTemplate.queryForObject(
            """
            INSERT INTO paper_orders
              (user_id, portfolio_id, portfolio_kind, position_id, instrument_id, side, quantity, scheduled_market_session_id,
               source_signal_id, fill_model_version_id, cost_model_version_id, can_user_cancel, idempotency_key)
            VALUES (?, ?, 'SANDBOX_PAPER', ?, ?, ?, ?, ?, ?, ?, ?, true, ?)
            RETURNING id
            """, UUID.class, userId, portfolioId, positionId, instrumentId, request.side(), request.quantity(), sessionId,
            optionalUuid(request.signalId()), fillModelId, costModelId, request.idempotencyKey()
        );
        return new OrderResult(details(orderId), false);
    }

    @Transactional
    public PaperOrderResponse cancel(UUID userId, UUID orderId) {
        OrderOwner order = jdbcTemplate.query(
            """
            SELECT po.id, po.status, po.can_user_cancel, p.kind
            FROM paper_orders po JOIN portfolios p ON p.id = po.portfolio_id
            WHERE po.id = ? AND po.user_id = ?
            """, rs -> rs.next() ? new OrderOwner(UUID.fromString(rs.getString("id")), rs.getString("status"), rs.getBoolean("can_user_cancel"), rs.getString("kind")) : null,
            orderId, userId
        );
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.");
        if ("RANKED_PAPER".equals(order.kind()) || !order.canCancel()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공식 랭킹 주문은 취소할 수 없습니다.");
        if (!"PENDING".equals(order.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "대기 주문만 취소할 수 있습니다.");
        jdbcTemplate.update("UPDATE paper_orders SET status = 'CANCELLED', updated_at = NOW() WHERE id = ?", order.id());
        return details(order.id());
    }

    private PaperOrderResponse details(UUID orderId) {
        List<PaperOrderResponse> rows = jdbcTemplate.query(
            """
            SELECT po.id, po.portfolio_id, po.position_id, i.symbol, po.side, po.quantity,
                   COALESCE(po.source_signal_id, po.source_position_signal_id) AS signal_id, po.status,
                   po.submitted_at, ms.session_date, cm.code AS cost_code, po.idempotency_key, po.rejection_reason
            FROM paper_orders po JOIN instruments i ON i.id = po.instrument_id
            JOIN market_sessions ms ON ms.id = po.scheduled_market_session_id
            JOIN cost_model_versions cm ON cm.id = po.cost_model_version_id WHERE po.id = ?
            """, (rs, rowNum) -> response(rs), orderId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.");
        return rows.getFirst();
    }

    private PaperOrderResponse response(java.sql.ResultSet rs) throws java.sql.SQLException {
        String status = rs.getString("status");
        return new PaperOrderResponse(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("portfolio_id")), optionalUuid(rs.getString("position_id")),
            rs.getString("symbol"), rs.getString("side"), rs.getLong("quantity"), optionalUuid(rs.getString("signal_id")), status,
            rs.getTimestamp("submitted_at").toInstant().atOffset(ZoneOffset.UTC), rs.getObject("session_date", LocalDate.class), "0",
            "BUY".equals(rs.getString("side")) && "PENDING".equals(status) ? "0" : "0", rs.getString("cost_code"), rs.getString("idempotency_key"), rs.getString("rejection_reason"));
    }

    private PortfolioRow portfolio(UUID userId, UUID portfolioId) {
        List<PortfolioRow> rows = jdbcTemplate.query("SELECT id, kind FROM portfolios WHERE id = ? AND user_id = ?", (rs, rowNum) -> new PortfolioRow(UUID.fromString(rs.getString("id")), rs.getString("kind")), portfolioId, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "포트폴리오를 찾을 수 없습니다.");
        return rows.getFirst();
    }

    private UUID instrumentId(String symbol) {
        List<UUID> rows = jdbcTemplate.query("SELECT id FROM instruments WHERE symbol = ?", (rs, rowNum) -> UUID.fromString(rs.getString("id")), symbol);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "종목 master에 없는 코드입니다. 키움 종목정보 import 뒤 다시 시도하세요.");
        return rows.getFirst();
    }

    private PositionRow position(UUID userId, UUID positionId) {
        return jdbcTemplate.query("SELECT id, portfolio_id, instrument_id, quantity FROM positions WHERE id = ? AND user_id = ?", rs -> rs.next() ? new PositionRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("portfolio_id")), UUID.fromString(rs.getString("instrument_id")), rs.getLong("quantity")) : null, positionId, userId);
    }

    private PositionRow openPosition(UUID userId, UUID portfolioId, UUID instrumentId) {
        return jdbcTemplate.query("SELECT id, portfolio_id, instrument_id, quantity FROM positions WHERE user_id = ? AND portfolio_id = ? AND instrument_id = ? AND status NOT IN ('CLOSED', 'ARCHIVED') LIMIT 1", rs -> rs.next() ? new PositionRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("portfolio_id")), UUID.fromString(rs.getString("instrument_id")), rs.getLong("quantity")) : null, userId, portfolioId, instrumentId);
    }

    private PositionRow reservePosition(UUID userId, UUID portfolioId, UUID instrumentId, long quantity, UUID signalId) {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO positions (user_id, portfolio_id, portfolio_kind, instrument_id, status, quantity, average_cost, realized_pnl, opened_at, first_execution_at, buy_signal_id)
            VALUES (?, ?, 'SANDBOX_PAPER', ?, 'OPEN', ?, 0, 0, NOW(), NOW(), ?)
            RETURNING id, portfolio_id, instrument_id, quantity
            """, (rs, rowNum) -> new PositionRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("portfolio_id")), UUID.fromString(rs.getString("instrument_id")), rs.getLong("quantity")), userId, portfolioId, instrumentId, quantity, signalId
        );
    }

    private boolean hasExecution(UUID positionId) { return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM position_executions WHERE position_id = ?)", Boolean.class, positionId)); }
    private boolean hasPendingOrder(UUID positionId) { return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM paper_orders WHERE position_id = ? AND status = 'PENDING')", Boolean.class, positionId)); }

    private UUID nextTradingSession() {
        List<UUID> sessions = jdbcTemplate.query("SELECT id FROM market_sessions WHERE is_trading_day = true AND open_at > NOW() ORDER BY open_at ASC LIMIT 1", (rs, rowNum) -> UUID.fromString(rs.getString("id")));
        if (sessions.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "다음 거래 세션이 없습니다. 시장 달력을 먼저 준비하세요.");
        return sessions.getFirst();
    }

    private UUID latestModelId(String table) {
        List<UUID> ids = jdbcTemplate.query("SELECT id FROM " + table + " ORDER BY effective_from DESC LIMIT 1", (rs, rowNum) -> UUID.fromString(rs.getString("id")));
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "페이퍼 체결·비용 모델이 없습니다.");
        return ids.getFirst();
    }

    private PaperOrderRow prior(UUID portfolioId, String key) {
        List<PaperOrderRow> rows = jdbcTemplate.query("SELECT id, instrument_id, position_id, side, quantity FROM paper_orders WHERE portfolio_id = ? AND idempotency_key = ?", (rs, rowNum) -> new PaperOrderRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("instrument_id")), optionalUuid(rs.getString("position_id")), rs.getString("side"), rs.getLong("quantity")), portfolioId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static boolean sameUuid(UUID value, String raw) { return value != null && value.equals(optionalUuid(raw)); }
    private static UUID uuid(String raw, String message) { UUID value = optionalUuid(raw); if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message); return value; }
    private static UUID optionalUuid(String raw) { if (raw == null || raw.isBlank()) return null; try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { return null; } }

    public record OrderResult(PaperOrderResponse order, boolean replayed) {}
    private record PortfolioRow(UUID id, String kind) {}
    private record PositionRow(UUID id, UUID portfolioId, UUID instrumentId, long quantity) {}
    private record PaperOrderRow(UUID id, UUID instrumentId, UUID positionId, String side, long quantity) {}
    private record OrderOwner(UUID id, String status, boolean canCancel, String kind) {}
}
