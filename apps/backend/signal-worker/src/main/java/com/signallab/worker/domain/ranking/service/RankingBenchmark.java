package com.signallab.worker.domain.ranking.service;

import com.signallab.worker.domain.backtest.service.BacktestEngine;
import java.util.ArrayList;
import java.util.List;

public final class RankingBenchmark {
    public static void main(String[] args) {
        int bars = 2_500 * 500;
        double slaMs = number(System.getenv("RANKING_BENCHMARK_SLA_MS"), 250);
        boolean fiveRequested = "true".equalsIgnoreCase(System.getenv("RANKING_ENABLE_FIVE_INDICATORS"));
        Result four = run(bars, 4, slaMs); Result five = run(bars, 5, slaMs);
        boolean enabled = fiveRequested && four.passed() && five.passed();
        System.out.printf("{\"bars\":%d,\"four\":%s,\"five\":%s,\"fiveRequested\":%s,\"fiveIndicatorSearchEnabled\":%s}%n",
            bars, four.json(), five.json(), fiveRequested, enabled);
        if (!four.passed()) throw new IllegalStateException("Four-indicator benchmark failed SLA " + slaMs + "ms");
        if (fiveRequested && !enabled) throw new IllegalStateException("Five-indicator benchmark failed SLA " + slaMs + "ms");
    }
    static Result run(int bars,int size,double slaMs){BacktestEngine engine=new BacktestEngine();List<long[]> conditions=new ArrayList<>();for(int c=0;c<size;c++){boolean[] values=new boolean[bars];for(int i=0;i<bars;i++)values[i]=((i*(c+3)+c)%17)<5;conditions.add(engine.toBitset(values));}long start=System.nanoTime();long[] result=engine.intersect(conditions);double elapsed=(System.nanoTime()-start)/1_000_000d;return new Result(size,conditions.stream().mapToLong(v->v.length*8L).sum(),result.length*8L,elapsed,slaMs,elapsed<=slaMs);}
    private static double number(String raw,double fallback){if(raw==null||raw.isBlank())return fallback;double value=Double.parseDouble(raw);if(!Double.isFinite(value)||value<=0)throw new IllegalArgumentException("RANKING_BENCHMARK_SLA_MS must be positive");return value;}
    record Result(int combinationSize,long bytes,long resultBytes,double elapsedMs,double slaMs,boolean passed){String json(){return String.format("{\"combinationSize\":%d,\"bytes\":%d,\"resultBytes\":%d,\"elapsedMs\":%.2f,\"slaMs\":%.2f,\"passed\":%s}",combinationSize,bytes,resultBytes,elapsedMs,slaMs,passed);}}
}
