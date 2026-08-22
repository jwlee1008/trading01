package com.signallab.domain.strategy.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Strategy(
    UUID id,
    UUID userId,
    String name,
    boolean isPublic,
    OffsetDateTime archivedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
