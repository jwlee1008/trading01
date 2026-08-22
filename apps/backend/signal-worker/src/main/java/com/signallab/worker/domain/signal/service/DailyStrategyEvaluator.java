package com.signallab.worker.domain.signal.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Pure, fail-closed D1 evaluator used by the Spring daily signal cycle. */
@Component
public class DailyStrategyEvaluator {

    public Evaluation evaluateLatestTransition(List<Candle> candles, Strategy strategy) {
        if (candles.size() < 2 || strategy.rules().isEmpty()) return Evaluation.notMatched();
        Map<String, List<Double>> cache = new LinkedHashMap<>();
        int latest = candles.size() - 1;
        boolean previous = matches(candles, strategy, latest - 1, cache, null);
        Map<String, Object> evidence = new LinkedHashMap<>();
        boolean current = matches(candles, strategy, latest, cache, evidence);
        return new Evaluation(!previous && current, current, evidence);
    }

    private boolean matches(List<Candle> candles, Strategy strategy, int index, Map<String, List<Double>> cache,
                            Map<String, Object> evidence) {
        List<Boolean> outcomes = new ArrayList<>();
        for (int ruleIndex = 0; ruleIndex < strategy.rules().size(); ruleIndex++) {
            Rule rule = strategy.rules().get(ruleIndex);
            Double currentLeft = valueAt(candles, rule.left(), index, cache);
            Double currentRight = valueAt(candles, rule.right(), index, cache);
            Double previousLeft = valueAt(candles, rule.left(), index - 1, cache);
            Double previousRight = valueAt(candles, rule.right(), index - 1, cache);
            boolean matched = currentLeft != null && currentRight != null
                && compare(rule.operator(), currentLeft, previousLeft, currentRight, previousRight);
            outcomes.add(matched);
            if (evidence != null) {
                String key = "rule." + ruleIndex;
                if (currentLeft != null) evidence.put(key + ".left", currentLeft);
                if (currentRight != null) evidence.put(key + ".right", currentRight);
                evidence.put(key + ".matched", matched);
            }
        }
        return strategy.logic() == Logic.AND ? outcomes.stream().allMatch(Boolean::booleanValue)
            : outcomes.stream().anyMatch(Boolean::booleanValue);
    }

    private Double valueAt(List<Candle> candles, Operand operand, int index, Map<String, List<Double>> cache) {
        if (index < 0) return null;
        if (operand instanceof Value value) return value.value();
        if (operand instanceof Close) return candles.get(index).close();
        Indicator indicator = (Indicator) operand;
        List<Double> values = cache.computeIfAbsent(indicator.cacheKey(), unused -> calculate(candles, indicator));
        return values.get(index);
    }

    private List<Double> calculate(List<Candle> candles, Indicator indicator) {
        int period = indicator.period();
        if (period < 2 || period > 500) throw new IllegalArgumentException("Invalid indicator period");
        return switch (indicator.code()) {
            case SMA -> sma(candles, period);
            case EMA -> ema(candles, period);
            case RSI -> rsi(candles, period);
            case MACD -> macd(candles, indicator);
            case BOLLINGER -> bollinger(candles, indicator);
            case VOLUME_SPIKE -> volumeSpike(candles, indicator);
            case STOCHASTIC -> stochastic(candles, indicator);
            case ATR -> atr(candles, period);
            case ADX -> adx(candles, period, indicator.outputKey());
            case OBV -> obv(candles);
        };
    }

    private List<Double> sma(List<Candle> candles, int period) {
        List<Double> values = empty(candles.size());
        double sum = 0;
        for (int index = 0; index < candles.size(); index++) {
            sum += candles.get(index).close();
            if (index >= period) sum -= candles.get(index - period).close();
            if (index >= period - 1) values.set(index, sum / period);
        }
        return values;
    }

    private List<Double> ema(List<Candle> candles, int period) {
        List<Double> values = empty(candles.size());
        double multiplier = 2d / (period + 1d);
        Double previous = null;
        for (int index = 0; index < candles.size(); index++) {
            if (index == period - 1) {
                double seed = 0;
                for (int cursor = 0; cursor < period; cursor++) seed += candles.get(cursor).close();
                previous = seed / period;
            } else if (index >= period && previous != null) {
                previous = (candles.get(index).close() - previous) * multiplier + previous;
            }
            if (previous != null) values.set(index, previous);
        }
        return values;
    }

    private List<Double> rsi(List<Candle> candles, int period) {
        List<Double> values = empty(candles.size());
        if (candles.size() <= period) return values;
        double gains = 0;
        double losses = 0;
        for (int index = 1; index <= period; index++) {
            double delta = candles.get(index).close() - candles.get(index - 1).close();
            gains += Math.max(0, delta);
            losses += Math.max(0, -delta);
        }
        double averageGain = gains / period;
        double averageLoss = losses / period;
        values.set(period, rsiValue(averageGain, averageLoss));
        for (int index = period + 1; index < candles.size(); index++) {
            double delta = candles.get(index).close() - candles.get(index - 1).close();
            averageGain = (averageGain * (period - 1) + Math.max(0, delta)) / period;
            averageLoss = (averageLoss * (period - 1) + Math.max(0, -delta)) / period;
            values.set(index, rsiValue(averageGain, averageLoss));
        }
        return values;
    }

    private List<Double> macd(List<Candle> candles, Indicator indicator) {
        int fast = indicator.intParam("fastPeriod", 12);
        int slow = indicator.intParam("slowPeriod", 26);
        int signalPeriod = indicator.intParam("signalPeriod", 9);
        if (fast >= slow) throw new IllegalArgumentException("MACD fastPeriod must be below slowPeriod");
        List<Double> fastValues = ema(candles, fast);
        List<Double> slowValues = ema(candles, slow);
        List<Double> macd = empty(candles.size());
        for (int index = 0; index < candles.size(); index++) {
            if (fastValues.get(index) != null && slowValues.get(index) != null) macd.set(index, fastValues.get(index) - slowValues.get(index));
        }
        List<Double> signal = emaSeries(macd, signalPeriod);
        return switch (indicator.outputKey()) {
            case "macd" -> macd;
            case "signal" -> signal;
            case "histogram" -> difference(macd, signal);
            default -> throw new IllegalArgumentException("Unsupported MACD output key");
        };
    }

    private List<Double> bollinger(List<Candle> candles, Indicator indicator) {
        int period = indicator.period();
        double deviations = indicator.doubleParam("standardDeviations", 2);
        if (deviations <= 0 || !Double.isFinite(deviations)) throw new IllegalArgumentException("Invalid Bollinger standard deviations");
        List<Double> middle = sma(candles, period);
        List<Double> output = empty(candles.size());
        for (int index = period - 1; index < candles.size(); index++) {
            Double mean = middle.get(index);
            if (mean == null) continue;
            double squares = 0;
            for (int cursor = index - period + 1; cursor <= index; cursor++) squares += Math.pow(candles.get(cursor).close() - mean, 2);
            double standardDeviation = Math.sqrt(squares / period);
            double upper = mean + deviations * standardDeviation;
            double lower = mean - deviations * standardDeviation;
            output.set(index, switch (indicator.outputKey()) {
                case "middle" -> mean;
                case "upper" -> upper;
                case "lower" -> lower;
                case "bandwidth" -> mean == 0 ? null : (upper - lower) / mean;
                case "percentB" -> upper == lower ? null : (candles.get(index).close() - lower) / (upper - lower);
                default -> throw new IllegalArgumentException("Unsupported BOLLINGER output key");
            });
        }
        return output;
    }

    private List<Double> volumeSpike(List<Candle> candles, Indicator indicator) {
        int period = indicator.period();
        List<Double> output = empty(candles.size());
        for (int index = period; index < candles.size(); index++) {
            long sum = 0;
            for (int cursor = index - period; cursor < index; cursor++) sum += candles.get(cursor).volume();
            double average = sum / (double) period;
            if (average == 0) continue;
            double ratio = candles.get(index).volume() / average;
            output.set(index, switch (indicator.outputKey()) {
                case "averageVolume" -> average;
                case "ratio" -> ratio;
                case "spike" -> ratio >= indicator.doubleParam("threshold", 2) ? 1d : 0d;
                default -> throw new IllegalArgumentException("Unsupported VOLUME_SPIKE output key");
            });
        }
        return output;
    }

    private List<Double> stochastic(List<Candle> candles, Indicator indicator) {
        int kPeriod = indicator.intParam("kPeriod", 14);
        int smoothPeriod = indicator.intParam("smoothKPeriod", 3);
        int dPeriod = indicator.intParam("dPeriod", 3);
        List<Double> raw = empty(candles.size());
        for (int index = kPeriod - 1; index < candles.size(); index++) {
            double highest = Double.NEGATIVE_INFINITY;
            double lowest = Double.POSITIVE_INFINITY;
            for (int cursor = index - kPeriod + 1; cursor <= index; cursor++) {
                highest = Math.max(highest, candles.get(cursor).high());
                lowest = Math.min(lowest, candles.get(cursor).low());
            }
            raw.set(index, highest == lowest ? 50d : (candles.get(index).close() - lowest) / (highest - lowest) * 100d);
        }
        List<Double> k = smaSeries(raw, smoothPeriod);
        return switch (indicator.outputKey()) {
            case "rawK" -> raw;
            case "k" -> k;
            case "d" -> smaSeries(k, dPeriod);
            default -> throw new IllegalArgumentException("Unsupported STOCHASTIC output key");
        };
    }

    private List<Double> atr(List<Candle> candles, int period) {
        List<Double> trueRanges = empty(candles.size());
        for (int index = 0; index < candles.size(); index++) {
            Candle candle = candles.get(index);
            double range = index == 0 ? candle.high() - candle.low() : Math.max(candle.high() - candle.low(),
                Math.max(Math.abs(candle.high() - candles.get(index - 1).close()), Math.abs(candle.low() - candles.get(index - 1).close())));
            trueRanges.set(index, range);
        }
        List<Double> output = empty(candles.size());
        if (candles.size() < period) return output;
        double current = 0;
        for (int index = 0; index < period; index++) current += trueRanges.get(index);
        current /= period;
        output.set(period - 1, current);
        for (int index = period; index < candles.size(); index++) { current = (current * (period - 1) + trueRanges.get(index)) / period; output.set(index, current); }
        return output;
    }

    private List<Double> adx(List<Candle> candles, int period, String outputKey) {
        List<Double> plusDi = empty(candles.size()), minusDi = empty(candles.size()), adx = empty(candles.size());
        if (candles.size() <= period) return output(outputKey, plusDi, minusDi, adx);
        double tr = 0, plus = 0, minus = 0;
        for (int index = 1; index <= period; index++) {
            Directional values = directional(candles.get(index - 1), candles.get(index)); tr += values.tr(); plus += values.plus(); minus += values.minus();
        }
        List<Double> dx = new ArrayList<>();
        for (int index = period; index < candles.size(); index++) {
            if (index > period) {
                Directional values = directional(candles.get(index - 1), candles.get(index));
                tr = tr - tr / period + values.tr(); plus = plus - plus / period + values.plus(); minus = minus - minus / period + values.minus();
            }
            double p = tr == 0 ? 0 : 100 * plus / tr, m = tr == 0 ? 0 : 100 * minus / tr;
            plusDi.set(index, p); minusDi.set(index, m);
            double value = p + m == 0 ? 0 : 100 * Math.abs(p - m) / (p + m);
            dx.add(value);
            if (dx.size() == period) adx.set(index, dx.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
            else if (dx.size() > period && adx.get(index - 1) != null) adx.set(index, (adx.get(index - 1) * (period - 1) + value) / period);
        }
        return output(outputKey, plusDi, minusDi, adx);
    }

    private Directional directional(Candle previous, Candle current) {
        double up = current.high() - previous.high(), down = previous.low() - current.low();
        return new Directional(Math.max(current.high() - current.low(), Math.max(Math.abs(current.high() - previous.close()), Math.abs(current.low() - previous.close()))),
            up > down && up > 0 ? up : 0, down > up && down > 0 ? down : 0);
    }

    private List<Double> output(String key, List<Double> plus, List<Double> minus, List<Double> adx) {
        return switch (key) { case "plusDI" -> plus; case "minusDI" -> minus; case "adx" -> adx; default -> throw new IllegalArgumentException("Unsupported ADX output key"); };
    }

    private List<Double> obv(List<Candle> candles) {
        List<Double> output = empty(candles.size());
        long current = 0;
        for (int index = 0; index < candles.size(); index++) {
            if (index > 0) current += candles.get(index).close() > candles.get(index - 1).close() ? candles.get(index).volume()
                : candles.get(index).close() < candles.get(index - 1).close() ? -candles.get(index).volume() : 0;
            output.set(index, (double) current);
        }
        return output;
    }

    private List<Double> emaSeries(List<Double> source, int period) {
        List<Double> result = empty(source.size());
        Double previous = null;
        for (int index = 0; index < source.size(); index++) {
            if (source.get(index) == null) { previous = null; continue; }
            if (previous == null) {
                if (index < period - 1) continue;
                double sum = 0;
                for (int cursor = index - period + 1; cursor <= index; cursor++) { if (source.get(cursor) == null) { sum = Double.NaN; break; } sum += source.get(cursor); }
                if (!Double.isFinite(sum)) continue;
                previous = sum / period;
            } else previous = (source.get(index) - previous) * 2d / (period + 1d) + previous;
            result.set(index, previous);
        }
        return result;
    }

    private List<Double> smaSeries(List<Double> source, int period) {
        List<Double> result = empty(source.size());
        for (int index = period - 1; index < source.size(); index++) {
            double sum = 0;
            for (int cursor = index - period + 1; cursor <= index; cursor++) { if (source.get(cursor) == null) { sum = Double.NaN; break; } sum += source.get(cursor); }
            if (Double.isFinite(sum)) result.set(index, sum / period);
        }
        return result;
    }

    private List<Double> difference(List<Double> left, List<Double> right) {
        List<Double> result = empty(left.size());
        for (int index = 0; index < left.size(); index++) if (left.get(index) != null && right.get(index) != null) result.set(index, left.get(index) - right.get(index));
        return result;
    }

    private double rsiValue(double gain, double loss) {
        if (loss == 0) return gain == 0 ? 50 : 100;
        return 100 - (100 / (1 + gain / loss));
    }

    private List<Double> empty(int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, null));
    }

    private boolean compare(Operator operator, double left, Double previousLeft, double right, Double previousRight) {
        return switch (operator) {
            case GT -> left > right;
            case GTE -> left >= right;
            case LT -> left < right;
            case LTE -> left <= right;
            case EQ -> Math.abs(left - right) <= Math.ulp(Math.max(1, Math.max(Math.abs(left), Math.abs(right))));
            case CROSSES_ABOVE -> previousLeft != null && previousRight != null && previousLeft <= previousRight && left > right;
            case CROSSES_BELOW -> previousLeft != null && previousRight != null && previousLeft >= previousRight && left < right;
        };
    }

    public record Candle(String sessionDate, double open, double high, double low, double close, long volume) {
        public Candle { Objects.requireNonNull(sessionDate); if (!Double.isFinite(open) || !Double.isFinite(high) || !Double.isFinite(low) || !Double.isFinite(close) || open <= 0 || high <= 0 || low <= 0 || close <= 0 || high < Math.max(open, close) || low > Math.min(open, close) || volume < 0) throw new IllegalArgumentException("Invalid candle"); }
        public Candle(String sessionDate, double close, long volume) { this(sessionDate, close, close, close, close, volume); }
    }
    public record Strategy(Logic logic, List<Rule> rules) { public Strategy { Objects.requireNonNull(logic); rules = List.copyOf(rules); } }
    public record Rule(Operand left, Operator operator, Operand right) { public Rule { Objects.requireNonNull(left); Objects.requireNonNull(operator); Objects.requireNonNull(right); } }
    public sealed interface Operand permits Close, Value, Indicator {}
    public record Close() implements Operand {}
    public record Value(double value) implements Operand { public Value { if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid value"); } }
    public record Indicator(Code code, String outputKey, Map<String, Double> params) implements Operand {
        public Indicator { Objects.requireNonNull(code); Objects.requireNonNull(outputKey); params = Map.copyOf(params); }
        public Indicator(Code code, int period) { this(code, defaultOutput(code), Map.of("period", (double) period)); }
        int period() { return intParam("period", code == Code.RSI ? 14 : 20); }
        int intParam(String name, int fallback) { double value = doubleParam(name, fallback); if (value != Math.rint(value)) throw new IllegalArgumentException("Indicator period must be an integer"); return (int) value; }
        double doubleParam(String name, double fallback) { return params.getOrDefault(name, fallback); }
        String cacheKey() { return code + ":" + outputKey + ":" + params; }
        private static String defaultOutput(Code code) { return switch (code) { case SMA -> "sma"; case EMA -> "ema"; case RSI -> "rsi"; case MACD -> "macd"; case BOLLINGER -> "middle"; case VOLUME_SPIKE -> "ratio"; case STOCHASTIC -> "k"; case ATR -> "atr"; case ADX -> "adx"; case OBV -> "obv"; }; }
    }
    private record Directional(double tr, double plus, double minus) {}
    public enum Code { SMA, EMA, RSI, MACD, BOLLINGER, VOLUME_SPIKE, STOCHASTIC, ATR, ADX, OBV }
    public enum Operator { GT, GTE, LT, LTE, EQ, CROSSES_ABOVE, CROSSES_BELOW }
    public enum Logic { AND, OR }
    public record Evaluation(boolean transitionedToMatch, boolean currentlyMatched, Map<String, Object> evidence) {
        static Evaluation notMatched() { return new Evaluation(false, false, Map.of()); }
    }
}
