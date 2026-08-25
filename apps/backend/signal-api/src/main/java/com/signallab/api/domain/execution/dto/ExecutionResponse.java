package com.signallab.api.domain.execution.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ExecutionResponse(
    UUID id,
    UUID portfolioId,
    UUID positionId,
    String symbol,
    String side,
    String price,
    long quantity,
    String fee,
    String tax,
    OffsetDateTime executedAt,
    String memo,
    UUID signalId,
    String idempotencyKey,
    UUID correctionOf
) {}
