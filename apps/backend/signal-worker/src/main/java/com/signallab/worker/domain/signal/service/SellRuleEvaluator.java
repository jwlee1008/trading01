package com.signallab.worker.domain.signal.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Pure evaluator for finalized sell-rule versions. Rates use DB fractional form (0.05 = 5%). */
public final class SellRuleEvaluator {
    public Evaluation evaluate(Input input) {
        if (input.close().signum() <= 0 || input.averageCost().signum() < 0 || input.highestClose().signum() <= 0
            || input.holdingSessions() < 0) throw new IllegalArgumentException("Invalid sell evaluation input");
        List<Match> matches = new ArrayList<>();
        if (input.stopLossRate() != null && input.close().compareTo(input.averageCost().multiply(BigDecimal.ONE.subtract(input.stopLossRate()))) <= 0)
            matches.add(new Match("STOP_LOSS", "STOP_LOSS"));
        if (input.takeProfitRate() != null && input.close().compareTo(input.averageCost().multiply(BigDecimal.ONE.add(input.takeProfitRate()))) >= 0)
            matches.add(new Match("TAKE_PROFIT", "TAKE_PROFIT"));
        if (input.trailingStopRate() != null && input.close().compareTo(input.highestClose().multiply(BigDecimal.ONE.subtract(input.trailingStopRate()))) <= 0)
            matches.add(new Match("TRAILING_STOP", "TRAILING_STOP"));
        if (input.maxHoldingSessions() != null && input.holdingSessions() >= input.maxHoldingSessions())
            matches.add(new Match("MAX_HOLDING_SESSIONS", "MAX_HOLDING_DAYS"));
        if (!input.technicalMatches().isEmpty()) {
            boolean technical = input.technicalLogic() == Logic.ALL
                ? input.technicalMatches().stream().allMatch(Boolean.TRUE::equals)
                : input.technicalMatches().stream().anyMatch(Boolean.TRUE::equals);
            if (technical) matches.add(new Match("TECHNICAL_GROUP", "TECHNICAL"));
        }
        return new Evaluation(!matches.isEmpty(), List.copyOf(matches));
    }

    public record Input(BigDecimal close, BigDecimal averageCost, BigDecimal highestClose, int holdingSessions,
                        BigDecimal stopLossRate, BigDecimal takeProfitRate, BigDecimal trailingStopRate,
                        Integer maxHoldingSessions, List<Boolean> technicalMatches, Logic technicalLogic) {
        public Input {
            technicalMatches = technicalMatches == null ? List.of() : List.copyOf(technicalMatches);
            if (!technicalMatches.isEmpty() && technicalLogic == null) throw new IllegalArgumentException("technicalLogic is required");
        }
    }
    public enum Logic { ANY, ALL }
    public record Match(String key, String kind) {}
    public record Evaluation(boolean triggered, List<Match> matches) {}
}
