package com.signallab.worker.domain.backtest.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BacktestEngineTest {
    private final BacktestEngine engine = new BacktestEngine();

    @Test
    void bitsetsAndOverlappingForwardWindowsMatchLegacyContract() {
        assertArrayEquals(engine.toBitset(new boolean[]{true,false,false,true}), engine.intersect(List.of(
            engine.toBitset(new boolean[]{true,true,false,true}), engine.toBitset(new boolean[]{true,false,true,true}))));
        BacktestEngine.Metrics metrics = engine.forwardMetrics(engine.toBitset(new boolean[]{true,true,false,false}),
            new double[]{100,101,110,120}, new double[]{100,100,105,110}, 2, 0, null, 1, null);
        assertEquals(1, metrics.signals());
    }

    @Test
    void separatesGrossNetBenchmarkAndExcessReturns() {
        BacktestEngine.Metrics metrics = engine.forwardMetrics(engine.toBitset(new boolean[]{true,false,false}),
            new double[]{100,110,130}, new double[]{100,100,120}, 1, 2, new double[]{0,3,0}, 1, null);
        assertEquals(10, metrics.meanGrossReturn()); assertEquals(8, metrics.meanNetReturn());
        assertEquals(3, metrics.meanBenchmarkReturn()); assertEquals(5, metrics.netExcessReturn());
        assertThrows(IllegalArgumentException.class, () -> engine.forwardMetrics(new long[1],new double[]{1},new double[]{1},1,-1,null,1,null));
    }

    @Test
    void buildsRollingWindowsAndPreservesVersionInputs() {
        assertEquals(2, engine.rollingWindows(20,8,4,4,4).size());
        BacktestEngine.Versions versions = new BacktestEngine.Versions("data-v1","calendar-v1",Map.of("SMA","1"),"engine-v1","formula-v1","uv1","fill1","cost1",7);
        BacktestEngine.VersionedResult result = engine.runVersioned(new BacktestEngine.VersionedInput(
            engine.toBitset(new boolean[]{true,true,false}),List.of("2026-01-01","2026-01-02","2026-01-03"),
            new double[]{100,110,120},new double[]{100,100,110},null,List.of(new BacktestEngine.Membership("2026-01-02",null)),
            List.of(1),0,1,new BacktestEngine.WalkForwardConfig(1,1,1,null),versions));
        assertEquals(versions,result.versions()); assertEquals(1,result.horizons().get(1).signals()); assertEquals(1,result.walkForward().size());
    }
}
