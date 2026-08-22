package com.signallab.worker.domain.ranking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RankedBuyAllocatorTest {
    @Test
    void ordersByStrengthLiquiditySymbolAndRespectsTenPercentWeight() {
        RankedBuyAllocator.Result result = new RankedBuyAllocator().allocate(new RankedBuyAllocator.Input(List.of(
            candidate("s-b", "000002", "0.9", "0.5", "10000"),
            candidate("s-a", "000001", "0.9", "0.5", "20000"),
            candidate("s-c", "000003", "0.8", "0.9", "5000")
        ), new BigDecimal("1000000"), new BigDecimal("250000"), Set.of(), 2, new BigDecimal("0.10")));

        assertEquals(List.of("000001", "000002"), result.selected().stream().map(s -> s.candidate().symbol()).toList());
        assertEquals(List.of(5L, 10L), result.selected().stream().map(RankedBuyAllocator.Selection::quantity).toList());
        assertEquals(new BigDecimal("50000"), result.remainingCash());
    }

    @Test
    void excludesOccupiedSymbolsAndCountsPendingTowardCapacity() {
        RankedBuyAllocator.Result result = new RankedBuyAllocator().allocate(new RankedBuyAllocator.Input(List.of(
            candidate("s-a", "005930", "1", "1", "80000"), candidate("s-b", "000660", "0.9", "1", "100000")
        ), new BigDecimal("1000000"), new BigDecimal("1000000"), Set.of("005930"), 2, new BigDecimal("0.10")));
        assertEquals(List.of("000660"), result.selected().stream().map(s -> s.candidate().symbol()).toList());
    }

    private RankedBuyAllocator.Candidate candidate(String id, String symbol, String strength, String liquidity, String cost) {
        return new RankedBuyAllocator.Candidate(id, symbol, new BigDecimal(strength), new BigDecimal(liquidity), new BigDecimal(cost));
    }
}
