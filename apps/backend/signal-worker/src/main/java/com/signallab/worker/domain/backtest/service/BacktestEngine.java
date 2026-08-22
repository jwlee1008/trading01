package com.signallab.worker.domain.backtest.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Version-independent forward-return primitives migrated from the TypeScript worker. */
public final class BacktestEngine {
    public long[] toBitset(boolean[] values) {
        long[] words = new long[(values.length + 63) / 64];
        for (int index = 0; index < values.length; index++) if (values[index]) words[index >>> 6] |= 1L << (index & 63);
        return words;
    }
    public long[] intersect(List<long[]> inputs) {
        if (inputs.isEmpty()) return new long[0];
        long[] result = Arrays.copyOf(inputs.getFirst(), inputs.getFirst().length);
        for (int set = 1; set < inputs.size(); set++) for (int word = 0; word < result.length; word++)
            result[word] &= word < inputs.get(set).length ? inputs.get(set)[word] : 0L;
        return result;
    }
    public long[] union(List<long[]> inputs) {
        int size = inputs.stream().mapToInt(value -> value.length).max().orElse(0); long[] result = new long[size];
        for (long[] input : inputs) for (int word = 0; word < input.length; word++) result[word] |= input[word];
        return result;
    }
    public long[] combine(Logic logic, List<long[]> inputs) { return logic == Logic.AND ? intersect(inputs) : union(inputs); }

    public List<WalkForwardWindow> rollingWindows(int bars, int trainBars, int validationBars, int holdoutBars, Integer stepBars) {
        int step = stepBars == null ? holdoutBars : stepBars;
        if (bars <= 0 || trainBars <= 0 || validationBars <= 0 || holdoutBars <= 0 || step <= 0)
            throw new IllegalArgumentException("Walk-forward bar counts must be positive integers");
        List<WalkForwardWindow> result = new ArrayList<>();
        for (int start = 0; start + trainBars + validationBars + holdoutBars <= bars; start += step) {
            int trainTo = start + trainBars - 1, validationTo = trainTo + validationBars;
            result.add(new WalkForwardWindow(new Range(start, trainTo), new Range(trainTo + 1, validationTo),
                new Range(validationTo + 1, validationTo + holdoutBars)));
        }
        return List.copyOf(result);
    }

    public Metrics forwardMetrics(long[] signalBits, double[] closes, double[] opens, int horizon,
                                  double roundTripCostPct, double[] benchmarkReturns, int minimumSignals,
                                  Integer evaluationEndIndex) {
        if (horizon <= 0) throw new IllegalArgumentException("horizon must be positive");
        if (closes.length != opens.length) throw new IllegalArgumentException("open and close series length mismatch");
        if (!Double.isFinite(roundTripCostPct) || roundTripCostPct < 0) throw new IllegalArgumentException("roundTripCostPct must be non-negative");
        if (benchmarkReturns != null && benchmarkReturns.length != closes.length) throw new IllegalArgumentException("benchmark return and price series length mismatch");
        List<Double> gross = new ArrayList<>(), net = new ArrayList<>(), benchmark = new ArrayList<>(), excess = new ArrayList<>();
        int priorExit = -1;
        for (int index = 0; index < closes.length; index++) {
            if (index / 64 >= signalBits.length || (signalBits[index >>> 6] & (1L << (index & 63))) == 0) continue;
            int entry = index + 1, exit = entry + horizon - 1;
            if (entry <= priorExit || exit >= closes.length || evaluationEndIndex != null && exit > evaluationEndIndex) continue;
            double entryOpen = opens[entry], exitClose = closes[exit];
            if (!Double.isFinite(entryOpen) || !Double.isFinite(exitClose) || entryOpen <= 0 || exitClose <= 0) continue;
            double grossReturn = (exitClose - entryOpen) / entryOpen * 100;
            double benchmarkReturn = benchmarkReturns == null ? 0 : benchmarkReturns[exit];
            if (!Double.isFinite(benchmarkReturn)) continue;
            double netReturn = grossReturn - roundTripCostPct;
            gross.add(grossReturn); net.add(netReturn); benchmark.add(benchmarkReturn); excess.add(netReturn - benchmarkReturn); priorExit = exit;
        }
        if (excess.isEmpty()) return Metrics.empty();
        double meanGross = average(gross), meanNet = average(net), meanBenchmark = average(benchmark), meanExcess = average(excess);
        double variance = excess.stream().mapToDouble(value -> Math.pow(value - meanExcess, 2)).average().orElse(0);
        double deviation = Math.sqrt(variance);
        List<Double> negative = excess.stream().filter(value -> value < 0).toList();
        double downside = negative.isEmpty() ? 0 : Math.sqrt(negative.stream().mapToDouble(value -> value * value).average().orElse(0));
        double equity = 1, peak = 1, drawdown = 0;
        for (double value : excess) { equity *= 1 + value / 100; peak = Math.max(peak, equity); drawdown = Math.min(drawdown, (equity - peak) / peak * 100); }
        double margin = 1.96 * deviation / Math.sqrt(excess.size());
        long hits = excess.stream().filter(value -> value > 0).count();
        return new Metrics(excess.size(), (double) hits / excess.size(), meanGross, meanGross, meanNet, meanBenchmark,
            meanExcess, drawdown, downside, 1 / (1 + deviation), new double[]{meanExcess - margin, meanExcess + margin}, excess.size() < minimumSignals);
    }

    public VersionedResult runVersioned(VersionedInput input) {
        if (input.sessionDates().size() != input.closes().length) throw new IllegalArgumentException("session date and price series length mismatch");
        boolean[] membership = new boolean[input.sessionDates().size()];
        for (int i = 0; i < membership.length; i++) { String date=input.sessionDates().get(i); membership[i]=input.membership().stream().anyMatch(r -> r.effectiveFrom().compareTo(date)<=0 && (r.effectiveTo()==null || r.effectiveTo().compareTo(date)>=0)); }
        long[] eligible = intersect(List.of(input.signalBits(), toBitset(membership)));
        Map<Integer,Metrics> horizons = metricsForRange(input, eligible, 0, membership.length - 1);
        List<WalkForwardResult> walk = new ArrayList<>();
        if (input.walkForward()!=null) for(WalkForwardWindow window:rollingWindows(membership.length,input.walkForward().trainBars(),input.walkForward().validationBars(),input.walkForward().holdoutBars(),input.walkForward().stepBars()))
            walk.add(new WalkForwardResult(window,metricsForRange(input,eligible,window.train().from(),window.train().to()),metricsForRange(input,eligible,window.validation().from(),window.validation().to()),metricsForRange(input,eligible,window.holdout().from(),window.holdout().to())));
        return new VersionedResult(input.versions(),horizons,List.copyOf(walk));
    }
    private Map<Integer,Metrics> metricsForRange(VersionedInput input,long[] eligible,int from,int to){boolean[] mask=new boolean[input.sessionDates().size()];for(int i=from;i<=to;i++)mask[i]=true;long[] ranged=intersect(List.of(eligible,toBitset(mask)));Map<Integer,Metrics> result=new LinkedHashMap<>();for(int h:input.horizons())result.put(h,forwardMetrics(ranged,input.closes(),input.opens(),h,input.roundTripCostPct(),input.benchmarkReturns(),input.minimumSignals(),to));return Map.copyOf(result);}
    private double average(List<Double> values){return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);}

    public enum Logic { AND, OR }
    public record Range(int from,int to){}
    public record WalkForwardWindow(Range train,Range validation,Range holdout){}
    public record Metrics(int signals,Double hitRate,Double meanReturn,Double meanGrossReturn,Double meanNetReturn,Double meanBenchmarkReturn,Double netExcessReturn,Double maxDrawdownPct,Double downsideDeviation,Double stability,double[] confidenceInterval95,boolean insufficientData){static Metrics empty(){return new Metrics(0,null,null,null,null,null,null,null,null,null,null,true);}}
    public record Membership(String effectiveFrom,String effectiveTo){}
    public record Versions(String datasetVersion,String calendarVersion,Map<String,String> indicatorVersionSet,String engineVersion,String formulaVersion,String universeVersionId,String fillModelVersion,String costModelVersion,long seed){}
    public record WalkForwardConfig(int trainBars,int validationBars,int holdoutBars,Integer stepBars){}
    public record VersionedInput(long[] signalBits,List<String> sessionDates,double[] closes,double[] opens,double[] benchmarkReturns,List<Membership> membership,List<Integer> horizons,double roundTripCostPct,int minimumSignals,WalkForwardConfig walkForward,Versions versions){}
    public record WalkForwardResult(WalkForwardWindow window,Map<Integer,Metrics> train,Map<Integer,Metrics> validation,Map<Integer,Metrics> holdout){}
    public record VersionedResult(Versions versions,Map<Integer,Metrics> horizons,List<WalkForwardResult> walkForward){}
}
