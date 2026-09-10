package com.agilityhub.core.clubs.dashboard.domain;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

/** Calendar boundaries are half-open instants, including DST-short and DST-long weeks. */
public record DashboardPeriod(LocalDate today, ZoneId zone) {
    public LocalDate weekStart() { return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    public LocalDate weekEnd() { return weekStart().plusDays(6); }
    public Instant from() { return weekStart().atStartOfDay(zone).toInstant(); }
    public Instant until() { return weekStart().plusWeeks(1).atStartOfDay(zone).toInstant(); }
    public Instant monthFrom() { return today.withDayOfMonth(1).atStartOfDay(zone).toInstant(); }
    public Instant monthUntil() { return today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant(); }
    public Instant activityFrom(int weeks) { return weekStart().minusWeeks(weeks - 1L).atStartOfDay(zone).toInstant(); }
    public boolean inWeek(Instant instant) { return !instant.isBefore(from()) && instant.isBefore(until()); }
}
