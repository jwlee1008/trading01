package com.signallab.worker.domain.marketdata.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Deterministic KRX session calendar. Holidays must be supplied explicitly. */
public final class TradingCalendar {

    private final Set<LocalDate> holidays;
    private final Set<LocalDate> extraSessions;

    public TradingCalendar(Collection<LocalDate> holidays, Collection<LocalDate> extraSessions) {
        this.holidays = Set.copyOf(new HashSet<>(holidays));
        this.extraSessions = Set.copyOf(new HashSet<>(extraSessions));
    }

    public boolean isSession(LocalDate date) {
        if (extraSessions.contains(date)) return true;
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    public LocalDate nextSessionAfter(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (!isSession(candidate)) candidate = candidate.plusDays(1);
        return candidate;
    }

    public LocalDate latestSessionOnOrBefore(LocalDate date) {
        LocalDate candidate = date;
        while (!isSession(candidate)) candidate = candidate.minusDays(1);
        return candidate;
    }
}
