package com.signallab.worker.domain.ranking.service;

import com.signallab.worker.global.config.WorkerProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PostgresRankedBuyCycleTest {
    @Test
    void disabledCycleDoesNotRequireDatabase() {
        PostgresRankedBuyCycle.Report report = new PostgresRankedBuyCycle(
            org.mockito.Mockito.mock(javax.sql.DataSource.class)).run(new WorkerProperties());
        assertEquals("disabled", report.source());
        assertEquals(0, report.ordersCreated());
    }
}
