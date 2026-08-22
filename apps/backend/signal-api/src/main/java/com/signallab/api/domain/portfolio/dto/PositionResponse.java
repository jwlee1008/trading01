package com.signallab.api.domain.portfolio.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PositionResponse(
    UUID id,
    UUID portfolioId,
    String symbol,
    String name,
    String status,
    long quantity,
    String averagePrice,
    String currentPrice,
    String highestClose,
    OffsetDateTime openedAt,
    String realizedPnl,
    UUID linkedSignalId,
    UUID sellRuleVersionId
) {}
