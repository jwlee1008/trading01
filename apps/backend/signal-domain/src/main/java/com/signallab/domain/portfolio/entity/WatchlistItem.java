package com.signallab.domain.portfolio.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WatchlistItem(
    UUID id,
    UUID userId,
    UUID instrumentId,
    OffsetDateTime createdAt
) {}
