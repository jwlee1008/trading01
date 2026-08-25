package com.signallab.api.domain.portfolio.dto;

import com.signallab.api.domain.execution.dto.ExecutionResponse;
import java.time.OffsetDateTime;
import java.util.List;
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
    boolean marketPriceAvailable,
    String highestClose,
    OffsetDateTime openedAt,
    String realizedPnl,
    UUID linkedSignalId,
    UUID sellRuleVersionId,
    List<ExecutionResponse> executions
) {}
