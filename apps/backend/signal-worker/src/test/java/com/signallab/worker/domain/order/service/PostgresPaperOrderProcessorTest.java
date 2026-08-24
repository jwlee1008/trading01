package com.signallab.worker.domain.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgresPaperOrderProcessorTest {
    @Test
    void rejectsInsufficientCashAndQuantityBeforeAtomicFill() {
        PostgresPaperOrderProcessor.Order buy = order("BUY", 10, "1000", 10, "200", "1000");
        assertEquals("INSUFFICIENT_CASH", buy.rejectionReason());
        PostgresPaperOrderProcessor.Order sell = order("SELL", 11, "100000", 10, "200", "1000");
        assertEquals("INSUFFICIENT_QUANTITY", sell.rejectionReason());
    }

    @Test
    void acceptsFundedLiquidOrderAndDefersMissingFinalCandle() {
        PostgresPaperOrderProcessor.Order ready = order("BUY", 2, "100000", 10, "200", "1000");
        assertTrue(ready.marketDataReady());
        assertNull(ready.rejectionReason());
        PostgresPaperOrderProcessor.Order missing = new PostgresPaperOrderProcessor.Order(
            UUID.randomUUID(), UUID.randomUUID(), "BUY", 1, "SANDBOX_PAPER", OffsetDateTime.now(),
            BigDecimal.valueOf(100000), 0, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, false, false);
        assertEquals(false, missing.marketDataReady());
    }

    private PostgresPaperOrderProcessor.Order order(String side, long quantity, String cash, long available, String volume, String open) {
        return new PostgresPaperOrderProcessor.Order(
            UUID.randomUUID(), UUID.randomUUID(), side, quantity, "SANDBOX_PAPER", OffsetDateTime.now(),
            new BigDecimal(cash), available, BigDecimal.valueOf(10), BigDecimal.valueOf(10), BigDecimal.valueOf(10),
            BigDecimal.valueOf(.001), BigDecimal.valueOf(.001), BigDecimal.valueOf(.002),
            new BigDecimal(open), new BigDecimal(volume), true, false);
    }
}
