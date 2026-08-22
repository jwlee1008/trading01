package com.signallab.worker.domain.ranking.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic ranked BUY allocator: strength, prior liquidity, then symbol. */
public final class RankedBuyAllocator {
    public Result allocate(Input input) {
        if (input.nav().signum() <= 0 || input.availableCash().signum() < 0 || input.maxWeight().signum() <= 0
            || input.maxWeight().compareTo(new BigDecimal("0.10")) > 0 || input.maxOpenPositions() < 1
            || input.maxOpenPositions() > 10) throw new IllegalArgumentException("Invalid ranked allocation policy");
        Set<String> occupied = new HashSet<>(input.occupiedSymbols());
        BigDecimal cash = input.availableCash();
        List<Selection> selected = new ArrayList<>();
        List<Candidate> sorted = input.candidates().stream().sorted(Comparator
            .comparing(Candidate::signalStrength, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Candidate::priorLiquidity, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Candidate::symbol)).toList();
        for (Candidate candidate : sorted) {
            if (occupied.size() >= input.maxOpenPositions()) break;
            if (occupied.contains(candidate.symbol()) || candidate.estimatedUnitCost().signum() <= 0) continue;
            BigDecimal budget = input.nav().multiply(input.maxWeight()).min(cash);
            long quantity;
            try { quantity = budget.divide(candidate.estimatedUnitCost(), 0, RoundingMode.FLOOR).longValueExact(); }
            catch (ArithmeticException error) { throw new IllegalArgumentException("Ranked quantity exceeds bigint", error); }
            if (quantity <= 0) continue;
            BigDecimal reserved = candidate.estimatedUnitCost().multiply(BigDecimal.valueOf(quantity));
            selected.add(new Selection(candidate, quantity, reserved));
            occupied.add(candidate.symbol());
            cash = cash.subtract(reserved);
        }
        return new Result(List.copyOf(selected), cash);
    }

    public record Candidate(String signalId, String symbol, BigDecimal signalStrength, BigDecimal priorLiquidity,
                            BigDecimal estimatedUnitCost) {}
    public record Input(List<Candidate> candidates, BigDecimal nav, BigDecimal availableCash, Set<String> occupiedSymbols,
                        int maxOpenPositions, BigDecimal maxWeight) {
        public Input { candidates = List.copyOf(candidates); occupiedSymbols = Set.copyOf(occupiedSymbols); }
    }
    public record Selection(Candidate candidate, long quantity, BigDecimal reservedCash) {}
    public record Result(List<Selection> selected, BigDecimal remainingCash) {}
}
