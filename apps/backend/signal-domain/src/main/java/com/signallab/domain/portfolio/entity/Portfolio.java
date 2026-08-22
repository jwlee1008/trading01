package com.signallab.domain.portfolio.entity;

import com.signallab.domain.portfolio.entity.PortfolioKind;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Portfolio(
    UUID id,
    UUID userId,
    PortfolioKind kind,
    String name,
    String currency,
    BigDecimal initialCash,
    BigDecimal cashBalance,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime archivedAt
) {}
