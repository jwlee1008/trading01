package com.signallab.domain.portfolio.entity;

import com.signallab.domain.portfolio.entity.PositionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Position(
    UUID id,
    UUID userId,
    UUID portfolioId,
    UUID instrumentId,
    PositionStatus status,
    long quantity,
    BigDecimal averageCost,
    BigDecimal realizedPnl,
    BigDecimal highestCompletedClose,
    OffsetDateTime openedAt,
    OffsetDateTime firstExecutionAt,
    OffsetDateTime closedAt,
    UUID strategyVersionId,
    UUID buySignalId,
    UUID universeVersionId,
    UUID sellRuleVersionId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
