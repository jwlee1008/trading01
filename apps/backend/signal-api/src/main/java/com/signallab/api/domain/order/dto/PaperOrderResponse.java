package com.signallab.api.domain.order.dto;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.UUID;

public record PaperOrderResponse(
    UUID id,
    UUID portfolioId,
    UUID positionId,
    String symbol,
    String side,
    long quantity,
    UUID signalId,
    String status,
    OffsetDateTime submittedAt,
    LocalDate scheduledSession,
    String estimatedPrice,
    String reservedCash,
    String costModelVersion,
    String idempotencyKey,
    String rejectionReason
) {}
