package com.signallab.worker.domain.signal.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DailyStrategyEvaluatorTest {

    private final DailyStrategyEvaluator evaluator = new DailyStrategyEvaluator();

    @Test
    void createsOnlyAFalseToTrueCrossingTransition() {
        List<DailyStrategyEvaluator.Candle> candles = List.of(
            new DailyStrategyEvaluator.Candle("2026-08-10", 10, 100),
            new DailyStrategyEvaluator.Candle("2026-08-11", 10, 100),
            new DailyStrategyEvaluator.Candle("2026-08-12", 9, 100),
            new DailyStrategyEvaluator.Candle("2026-08-13", 12, 100)
        );
        DailyStrategyEvaluator.Strategy strategy = new DailyStrategyEvaluator.Strategy(
            DailyStrategyEvaluator.Logic.AND,
            List.of(new DailyStrategyEvaluator.Rule(new DailyStrategyEvaluator.Close(),
                DailyStrategyEvaluator.Operator.CROSSES_ABOVE,
                new DailyStrategyEvaluator.Indicator(DailyStrategyEvaluator.Code.SMA, 2)))
        );

        DailyStrategyEvaluator.Evaluation result = evaluator.evaluateLatestTransition(candles, strategy);

        assertTrue(result.transitionedToMatch());
        assertTrue(result.currentlyMatched());
        assertTrue((Boolean) result.evidence().get("rule.0.matched"));
    }

    @Test
    void doesNotEmitWhenConditionWasAlreadyTrue() {
        List<DailyStrategyEvaluator.Candle> candles = List.of(
            new DailyStrategyEvaluator.Candle("2026-08-10", 10, 100),
            new DailyStrategyEvaluator.Candle("2026-08-11", 10, 100),
            new DailyStrategyEvaluator.Candle("2026-08-12", 12, 100),
            new DailyStrategyEvaluator.Candle("2026-08-13", 13, 100)
        );
        DailyStrategyEvaluator.Strategy strategy = new DailyStrategyEvaluator.Strategy(
            DailyStrategyEvaluator.Logic.AND,
            List.of(new DailyStrategyEvaluator.Rule(new DailyStrategyEvaluator.Close(), DailyStrategyEvaluator.Operator.GT,
                new DailyStrategyEvaluator.Indicator(DailyStrategyEvaluator.Code.SMA, 2)))
        );

        assertFalse(evaluator.evaluateLatestTransition(candles, strategy).transitionedToMatch());
    }
}
