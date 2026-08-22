package com.signallab.api.domain.strategy.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record StrategyVersionResponse(
    UUID id,
    UUID userId,
    UUID strategyId,
    int version,
    String name,
    String universeVersionId,
    String logic,
    List<JsonNode> rules,
    boolean alertsEnabled,
    boolean isPublic,
    boolean locked,
    OffsetDateTime createdAt
) {}
