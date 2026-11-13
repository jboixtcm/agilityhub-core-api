package com.agilityhub.core.payments.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

/**
 * SEPA-04 (c), S12 R-12-15 (owner decision of 13-11-2026): the TARGET calendar SEPA direct debits settle on — every Monday to
 * Friday except 1 January, Good Friday, Easter Monday, 1 May, 25 and 26 December.
 */
public final class TargetCalendar {
    private static final Set<MonthDay> CLOSED = Set.of(MonthDay.of(1, 1), MonthDay.of(5, 1), MonthDay.of(12, 25), MonthDay.of(12, 26));
    private TargetCalendar() { }

    public static boolean businessDay(LocalDate day) {
        if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY || CLOSED.contains(MonthDay.from(day))) { return false; }
        var easter = easterSunday(day.getYear());
        return !day.equals(easter.minusDays(2)) && !day.equals(easter.plusDays(1));
    }

    /** The {@code days}-th TARGET business day after {@code from}; {@code from} itself when {@code days} is 0. */
    public static LocalDate plusBusinessDays(LocalDate from, int days) {
        if (days < 0) { throw new IllegalArgumentException("days must not be negative"); }
        var day = from;
        for (int left = days; left > 0; ) {
            day = day.plusDays(1);
            if (businessDay(day)) { left--; }
        }
        return day;
    }

    /** Easter Sunday of the Gregorian {@code year} (the anonymous Gregorian algorithm). */
    static LocalDate easterSunday(int year) {
        int a = year % 19, b = year / 100, c = year % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }
}
