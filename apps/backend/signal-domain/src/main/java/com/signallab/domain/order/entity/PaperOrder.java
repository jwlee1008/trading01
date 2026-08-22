package com.signallab.domain.order.entity;

import com.signallab.domain.order.entity.OrderSide;
import com.signallab.domain.order.entity.PaperOrderStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaperOrder(
    UUID id,
    UUID userId,
    UUID portfolioId,
    UUID positionId,
    UUID instrumentId,
    OrderSide side,
    long quantity,
    PaperOrderStatus status,
    UUID scheduledMarketSessionId,
    UUID sourceSignalId,
    UUID sourcePositionSignalId,
    UUID fillModelVersionId,
    UUID costModelVersionId,
    boolean canUserCancel,
    String idempotencyKey,
    String rejectionReason,
    OffsetDateTime submittedAt,
    OffsetDateTime updatedAt
) {}
