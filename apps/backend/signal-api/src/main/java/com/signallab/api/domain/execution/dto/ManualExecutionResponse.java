package com.signallab.api.domain.execution.dto;

import com.signallab.api.domain.portfolio.dto.PositionResponse;

public record ManualExecutionResponse(
    ExecutionResponse execution,
    PositionResponse position,
    boolean replayed,
    String stateNotice
) {}
