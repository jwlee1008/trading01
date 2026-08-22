package com.signallab.worker.domain.marketdata.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Normalizes provider candles and fails closed on gaps or malformed OHLC data. */
public final class MarketCandleNormalizer {

    public Result normalize(List<Candle> input, TradingCalendar calendar, LocalDate expectedThrough) {
        LocalDate expectedFrom = input.stream().map(Candle::sessionDate).min(LocalDate::compareTo).orElse(expectedThrough);
        return normalize(input, calendar, expectedFrom, expectedThrough);
    }

    public Result normalize(List<Candle> input, TradingCalendar calendar, LocalDate expectedFrom, LocalDate expectedThrough) {
        List<Candle> sorted = new ArrayList<>(input);
        boolean outOfOrder = false;
        for (int index = 1; index < input.size(); index++) {
            if (input.get(index - 1).sessionDate().isAfter(input.get(index).sessionDate())) {
                outOfOrder = true;
                break;
            }
        }
        sorted.sort(Comparator.comparing(Candle::sessionDate));

        Set<LocalDate> seen = new HashSet<>();
        List<LocalDate> duplicateSessions = new ArrayList<>();
        List<Candle> valid = new ArrayList<>();
        for (Candle candle : sorted) {
            if (!seen.add(candle.sessionDate())) {
                duplicateSessions.add(candle.sessionDate());
                continue;
            }
            validate(candle);
            valid.add(candle);
        }

        List<LocalDate> missingSessions = new ArrayList<>();
        if (!expectedFrom.isAfter(expectedThrough)) {
            LocalDate cursor = expectedFrom;
            Set<LocalDate> available = new HashSet<>();
            valid.forEach(candle -> available.add(candle.sessionDate()));
            while (!cursor.isAfter(expectedThrough)) {
                if (calendar.isSession(cursor) && !available.contains(cursor)) missingSessions.add(cursor);
                cursor = cursor.plusDays(1);
            }
        }
        return new Result(List.copyOf(valid), outOfOrder, List.copyOf(duplicateSessions), List.copyOf(missingSessions));
    }

    private void validate(Candle candle) {
        if (candle.open() <= 0 || candle.high() <= 0 || candle.low() <= 0 || candle.close() <= 0
            || candle.volume() < 0 || candle.high() < Math.max(candle.open(), candle.close())
            || candle.low() > Math.min(candle.open(), candle.close())) {
            throw new IllegalArgumentException("Invalid provider candle for " + candle.sessionDate());
        }
    }

    public record Candle(LocalDate sessionDate, double open, double high, double low, double close, long volume) {}
    public record Result(List<Candle> candles, boolean outOfOrder, List<LocalDate> duplicateSessions,
                         List<LocalDate> missingSessions) {
        public boolean safeToEvaluate() {
            return !outOfOrder && duplicateSessions.isEmpty() && missingSessions.isEmpty();
        }
    }
}
