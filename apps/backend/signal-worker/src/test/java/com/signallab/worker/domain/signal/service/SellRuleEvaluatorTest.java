package com.signallab.worker.domain.signal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SellRuleEvaluatorTest {
    private final SellRuleEvaluator evaluator = new SellRuleEvaluator();

    @Test
    void combinesPriceHoldingAndTechnicalMatches() {
        SellRuleEvaluator.Evaluation result = evaluator.evaluate(new SellRuleEvaluator.Input(
            new BigDecimal("89"), new BigDecimal("100"), new BigDecimal("110"), 20,
            new BigDecimal("0.10"), new BigDecimal("0.20"), new BigDecimal("0.15"), 20,
            List.of(true, false), SellRuleEvaluator.Logic.ANY
        ));
        assertTrue(result.triggered());
        assertEquals(List.of("STOP_LOSS", "TRAILING_STOP", "MAX_HOLDING_SESSIONS", "TECHNICAL_GROUP"),
            result.matches().stream().map(SellRuleEvaluator.Match::key).toList());
    }

    @Test
    void allTechnicalRulesMustMatchAndEmptyRuleSetDoesNotTrigger() {
        SellRuleEvaluator.Evaluation result = evaluator.evaluate(new SellRuleEvaluator.Input(
            new BigDecimal("101"), new BigDecimal("100"), new BigDecimal("105"), 2,
            null, null, null, null, List.of(true, false), SellRuleEvaluator.Logic.ALL
        ));
        assertFalse(result.triggered());

        SellRuleEvaluator.Evaluation takeProfit = evaluator.evaluate(new SellRuleEvaluator.Input(
            new BigDecimal("120"), new BigDecimal("100"), new BigDecimal("120"), 2,
            null, new BigDecimal("0.20"), null, null, List.of(), null
        ));
        assertTrue(takeProfit.triggered());
    }
}
