package com.signallab.api.domain.portfolio.dto;

import java.util.List;
import java.util.UUID;

public record PortfolioResponse(
    UUID id,
    UUID userId,
    String name,
    String kind,
    String cash,
    String nav,
    List<PositionResponse> positions
) {}
