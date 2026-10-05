package com.agilityhub.core.clubs.census.inactivity.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.*;
import java.util.Map;
import java.util.Objects;

/** S13 R-13-01/03: a change may flip only months starting at E(today), in the club's zone. */
public final class InactivityCalendar {
    private InactivityCalendar() { }
    public static YearMonth earliest(Instant today, int deadlineDay, ZoneId zone) {
        return earliest(today.atZone(zone).toLocalDate(), deadlineDay);
    }
    public static YearMonth earliest(LocalDate today, int deadlineDay) {
        return YearMonth.from(today).plusMonths(today.getDayOfMonth() <= Math.min(deadlineDay, today.lengthOfMonth()) ? 1 : 2);
    }
    public static boolean mayChange(YearMonth month, LocalDate today, int deadlineDay) { return !month.isBefore(earliest(today, deadlineDay)); }
    public static Instant from(YearMonth month, ZoneId zone) { return month.atDay(1).atStartOfDay(zone).toInstant(); }
    /** Exclusive end avoids losing fractional seconds or mishandling a daylight-saving transition. */
    public static Instant until(YearMonth month, ZoneId zone) { return month == null ? null : from(month.plusMonths(1), zone); }
    public static boolean covers(YearMonth from, YearMonth to, YearMonth month) {
        return !month.isBefore(from) && (to == null || !month.isAfter(to));
    }
    public static void range(YearMonth from, YearMonth to) {
        if (from == null || to != null && to.isBefore(from)) { throw new ApiException(ErrorCode.INACTIVITY_INVALID_RANGE); }
    }
    public static void request(YearMonth from, YearMonth to, YearMonth earliest, int maxAhead, boolean override) {
        range(from, to);
        if (!override && from.isBefore(earliest)) { deadline(earliest); }
        if (from.isAfter(earliest.plusMonths(maxAhead))) { throw new ApiException(ErrorCode.INACTIVITY_INVALID_RANGE); }
    }
    /** Compare the changed interval boundaries, including an open end, without iterating unbounded months. */
    public static void change(YearMonth oldFrom, YearMonth oldTo, YearMonth from, YearMonth to, YearMonth earliest) {
        range(from, to);
        if (!oldFrom.equals(from) && (oldFrom.isBefore(earliest) || from.isBefore(earliest))) { deadline(earliest); }
        if (!Objects.equals(oldTo, to)) {
            YearMonth firstChanged = oldTo == null ? to.plusMonths(1) : to == null ? oldTo.plusMonths(1)
                    : (oldTo.isBefore(to) ? oldTo : to).plusMonths(1);
            if (firstChanged.isBefore(earliest)) { deadline(earliest); }
        }
    }
    public static boolean overlapsOrAdjacent(YearMonth a, YearMonth b, YearMonth c, YearMonth d) {
        return (b == null || !b.plusMonths(1).isBefore(c)) && (d == null || !d.plusMonths(1).isBefore(a));
    }
    public static void deadline(YearMonth earliest) { throw new ApiException(ErrorCode.INACTIVITY_DEADLINE_PASSED, Map.of("earliestMonth", earliest.toString())); }
}
