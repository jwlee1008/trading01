package com.signallab.api.domain.order.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

public record SellRuleRequest(
    BigDecimal stopLossPct,
    BigDecimal takeProfitPct,
    BigDecimal trailingStopPct,
    Integer maxHoldingSessions,
    String technicalLogic,
    List<JsonNode> technicalRules,
    Boolean manualOnly
) {}
