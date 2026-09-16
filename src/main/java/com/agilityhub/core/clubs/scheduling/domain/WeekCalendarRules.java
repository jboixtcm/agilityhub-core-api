package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.time.temporal.*;

public final class WeekCalendarRules {
    private WeekCalendarRules() { }
    public record ResolvedTime(Instant instant, boolean shifted, LocalDateTime resolvedLocal) { }
    public static LocalDate monday(LocalDate date) { return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    public static void requireMonday(LocalDate date) {
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
    public static int isoYear(LocalDate date) { return date.get(IsoFields.WEEK_BASED_YEAR); }
    public static int isoWeek(LocalDate date) { return date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR); }
    public static ResolvedTime resolve(LocalDate date, LocalTime time, ZoneId zone) {
        var local = LocalDateTime.of(date, time); var zoned = ZonedDateTime.of(date, time, zone);
        return new ResolvedTime(zoned.toInstant(), !local.equals(zoned.toLocalDateTime()), zoned.toLocalDateTime());
    }
    public static Instant reviewAt(LocalDate date, LocalTime time, ZoneId zone) { return resolve(date, time, zone).instant(); }
}
