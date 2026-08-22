package com.signallab.domain.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic demo-only close series that produces exact latest-day indicator transitions. */
public final class IndicatorTestPattern {
    public static final String NONE = "NONE";
    private IndicatorTestPattern() {}

    public static List<String> supported() {
        return List.of(NONE, "RSI_ONLY", "EMA_ONLY", "BOLLINGER_ONLY", "RSI_EMA", "RSI_BOLLINGER", "EMA_BOLLINGER", "RSI_EMA_BOLLINGER");
    }

    public static Result apply(List<Double> original, String rawPattern) {
        String pattern = rawPattern == null ? NONE : rawPattern.toUpperCase(Locale.ROOT);
        int target = mask(pattern);
        if (target == 0) return new Result(List.copyOf(original), false, false, false);
        if (original.size() < 80) throw new IllegalArgumentException("지표 테스트 패턴에는 최소 80개의 일봉이 필요합니다.");
        double scale = Math.max(1_000d, original.get(original.size() - 80));
        for (int attempt = 0; attempt < 20_000; attempt++) {
            Random random = new Random(0x5f3759dfL + target * 100_003L + attempt * 97L);
            List<Double> candidate = new ArrayList<>(original);
            double value = scale;
            int start = candidate.size() - 80;
            for (int offset = 0; offset < 78; offset++) {
                if (offset > 0) value = Math.max(scale * .2, value * (1d + (random.next() - .55d) * .08d));
                candidate.set(start + offset, value);
            }
            candidate.set(start + 78, value * (.65d + random.next() * .5d));
            candidate.set(start + 79, candidate.get(start + 78) * (.85d + random.next() * .5d));
            Flags flags = flags(candidate);
            if (flags.mask() == target) return new Result(candidate, flags.rsi(), flags.ema(), flags.bollinger());
        }
        throw new IllegalStateException("요청한 지표 테스트 패턴을 생성하지 못했습니다.");
    }

    private static int mask(String pattern) {
        if (!supported().contains(pattern)) throw new IllegalArgumentException("지원하지 않는 지표 테스트 패턴입니다.");
        if (NONE.equals(pattern)) return 0;
        return (pattern.contains("RSI") ? 1 : 0) | (pattern.contains("EMA") ? 2 : 0) | (pattern.contains("BOLLINGER") ? 4 : 0);
    }

    private static Flags flags(List<Double> closes) {
        int latest = closes.size() - 1;
        List<Double> ema = ema(closes, 20), rsi = rsi(closes, 14);
        boolean rsiCross = rsi.get(latest - 1) <= 30d && rsi.get(latest) > 30d;
        boolean emaCross = closes.get(latest - 1) <= ema.get(latest - 1) && closes.get(latest) > ema.get(latest);
        boolean bandCross = closes.get(latest - 1) <= lower(closes, latest - 1) && closes.get(latest) > lower(closes, latest);
        return new Flags(rsiCross, emaCross, bandCross);
    }

    private static List<Double> ema(List<Double> values, int period) {
        List<Double> output = new ArrayList<>(); for (int i=0;i<values.size();i++) output.add(null);
        double current = 0; for (int i=0;i<period;i++) current += values.get(i); current /= period; output.set(period-1,current);
        double multiplier=2d/(period+1d); for(int i=period;i<values.size();i++){current=(values.get(i)-current)*multiplier+current;output.set(i,current);} return output;
    }
    private static List<Double> rsi(List<Double> values, int period) {
        List<Double> output = new ArrayList<>(); for (int i=0;i<values.size();i++) output.add(null);
        double gains=0,losses=0; for(int i=1;i<=period;i++){double d=values.get(i)-values.get(i-1);gains+=Math.max(0,d);losses+=Math.max(0,-d);} gains/=period;losses/=period;output.set(period,rsiValue(gains,losses));
        for(int i=period+1;i<values.size();i++){double d=values.get(i)-values.get(i-1);gains=(gains*(period-1)+Math.max(0,d))/period;losses=(losses*(period-1)+Math.max(0,-d))/period;output.set(i,rsiValue(gains,losses));} return output;
    }
    private static double rsiValue(double gain,double loss){if(loss==0)return 100;if(gain==0)return 0;return 100d-100d/(1d+gain/loss);}
    private static double lower(List<Double> values,int index){double mean=0;for(int i=index-19;i<=index;i++)mean+=values.get(i);mean/=20;double squares=0;for(int i=index-19;i<=index;i++)squares+=Math.pow(values.get(i)-mean,2);return mean-2d*Math.sqrt(squares/20d);}
    private record Flags(boolean rsi,boolean ema,boolean bollinger){int mask(){return(rsi?1:0)|(ema?2:0)|(bollinger?4:0);}}
    private static final class Random { private long state; Random(long seed){state=seed;} double next(){state=(state*1664525L+1013904223L)&0xffffffffL;return state/(double)(1L<<32);} }
    public record Result(List<Double> closes, boolean rsi, boolean ema, boolean bollinger) {}
}
