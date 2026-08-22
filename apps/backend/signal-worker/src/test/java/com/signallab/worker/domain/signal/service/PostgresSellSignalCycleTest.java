package com.signallab.worker.domain.signal.service;

import com.signallab.worker.global.config.WorkerProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PostgresSellSignalCycleTest {
    @Test
    void cycleIsFailClosedAndDoesNotRequireDatabaseWhenDisabled() {
        WorkerProperties properties = new WorkerProperties();
        PostgresSellSignalCycle.Report report = new PostgresSellSignalCycle(new ObjectMapper()).run(properties);
        assertEquals("disabled", report.source());
        assertEquals(0, report.signalsCreated());
        assertEquals(0, report.rankedOrdersCreated());
    }

    @Test
    void parsesLegacyTechnicalRuleStoredByApi() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PostgresSellSignalCycle cycle = new PostgresSellSignalCycle(mapper);
        DailyStrategyEvaluator.Rule rule = cycle.parseRule(mapper.readTree(
            "{\"indicatorId\":\"RSI\",\"operator\":\"LTE\",\"value\":30,\"params\":{\"period\":14}}"
        ));
        DailyStrategyEvaluator.Indicator indicator = assertInstanceOf(DailyStrategyEvaluator.Indicator.class, rule.left());
        assertEquals(DailyStrategyEvaluator.Code.RSI, indicator.code());
        assertEquals(14d, indicator.params().get("period"));
        assertEquals(DailyStrategyEvaluator.Operator.LTE, rule.operator());
    }
}
