package com.agilityhub.core.platform.application.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * S15 R-15-02 and R-15-05 arithmetic, without Spring or Mongo. Everything is compared as UTC instants;
 * the club zone only turns a local date and hour into an occurrence.
 */
public final class JobOccurrences {
    /** R-15-05: a run at most two minutes after its occurrence is on schedule. */
    public static final Duration ON_TIME = Duration.ofMinutes(2);
    public enum Outcome { SCHEDULE, CATCH_UP, MISSED_WINDOW }

    private JobOccurrences() { }

    /** Java semantics, identical to S06 R-06-14: a gap shifts forward, an overlap takes the first (earlier-offset) instant. */
    public static Instant occurrence(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toInstant();
    }

    /** The latest occurrence not after `now`; empty only for a monthly job whose day is 0 («never»). */
    public static Optional<Instant> lastDue(Instant now, JobSchedule schedule, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return switch (schedule.kind()) {
            case CONTINUOUS -> Optional.of(now.truncatedTo(ChronoUnit.MINUTES));
            case DAILY -> Optional.of(latest(today, schedule.localTime(), zone, now, 1));
            case WEEKLY -> Optional.of(latest(today.with(TemporalAdjusters.previousOrSame(schedule.dayOfWeek())), schedule.localTime(), zone, now, 7));
            case MONTHLY -> {
                if (schedule.dayOfMonth() == null || schedule.dayOfMonth() <= 0) { yield Optional.empty(); }
                LocalDate date = monthDay(YearMonth.from(today), schedule.dayOfMonth());
                Instant candidate = occurrence(date, schedule.localTime(), zone);
                if (candidate.isAfter(now)) { candidate = occurrence(monthDay(YearMonth.from(today).minusMonths(1), schedule.dayOfMonth()), schedule.localTime(), zone); }
                yield Optional.of(candidate);
            }
        };
    }

    /** The first occurrence strictly after `now` (D11 `nextScheduledForLocal`). */
    public static Optional<Instant> next(Instant now, JobSchedule schedule, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return switch (schedule.kind()) {
            case CONTINUOUS -> Optional.of(now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1)));
            case DAILY -> Optional.of(earliest(today, schedule.localTime(), zone, now, 1));
            case WEEKLY -> Optional.of(earliest(today.with(TemporalAdjusters.nextOrSame(schedule.dayOfWeek())), schedule.localTime(), zone, now, 7));
            case MONTHLY -> {
                if (schedule.dayOfMonth() == null || schedule.dayOfMonth() <= 0) { yield Optional.empty(); }
                Instant candidate = occurrence(monthDay(YearMonth.from(today), schedule.dayOfMonth()), schedule.localTime(), zone);
                if (!candidate.isAfter(now)) { candidate = occurrence(monthDay(YearMonth.from(today).plusMonths(1), schedule.dayOfMonth()), schedule.localTime(), zone); }
                yield Optional.of(candidate);
            }
        };
    }

    /** R-15-05: on time, inside the catalog catch-up window, or missed. Continuous jobs are always on time. */
    public static Outcome triggerFor(Instant now, Instant scheduledFor, CatchUpWindow window, ZoneId zone) {
        if (window == CatchUpWindow.CONTINUOUS || Duration.between(scheduledFor, now).compareTo(ON_TIME) <= 0) { return Outcome.SCHEDULE; }
        return window.covers(scheduledFor, now, zone) ? Outcome.CATCH_UP : Outcome.MISSED_WINDOW;
    }

    private static Instant latest(LocalDate date, LocalTime time, ZoneId zone, Instant now, int stepDays) {
        Instant candidate = occurrence(date, time, zone);
        return candidate.isAfter(now) ? occurrence(date.minusDays(stepDays), time, zone) : candidate;
    }
    private static Instant earliest(LocalDate date, LocalTime time, ZoneId zone, Instant now, int stepDays) {
        Instant candidate = occurrence(date, time, zone);
        return candidate.isAfter(now) ? candidate : occurrence(date.plusDays(stepDays), time, zone);
    }
    private static LocalDate monthDay(YearMonth month, int day) { return month.atDay(Math.min(day, month.lengthOfMonth())); }
}
