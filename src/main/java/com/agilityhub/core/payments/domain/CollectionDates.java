package com.agilityhub.core.payments.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/** Collection and presentation use the TARGET settlement calendar. All dates are club-local. */
public final class CollectionDates {
    private CollectionDates() { }

    public static LocalDate defaultDate(YearMonth billed, int dayOfMonth) {
        if (dayOfMonth < 0 || dayOfMonth > 28) { throw new IllegalArgumentException("billing.sepa.collectionDayOfMonth"); }
        return following(dayOfMonth == 0 ? billed.atEndOfMonth() : billed.atDay(dayOfMonth));
    }

    public static LocalDate earliest(LocalDate generation, Integer leadDays) {
        int days = leadDays == null ? 2 : leadDays;
        if (days < 1 || days > 30) { throw new IllegalArgumentException("billing.sepa.leadBusinessDays"); }
        LocalDate day = following(generation);
        for (int i = 0; i < days; i++) { day = following(day.plusDays(1)); }
        return day;
    }

    public static LocalDate following(LocalDate date) {
        while (!isSettlementDay(date)) { date = date.plusDays(1); }
        return date;
    }

    private static boolean isSettlementDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) { return false; }
        int month = date.getMonthValue(), day = date.getDayOfMonth();
        if (((month == 1 || month == 5) && day == 1) || (month == 12 && (day == 25 || day == 26))) { return false; }
        if (month != 3 && month != 4) { return true; }
        LocalDate easter = easterSunday(date.getYear());
        return !date.equals(easter.minusDays(2)) && !date.equals(easter.plusDays(1));
    }

    /** Gregorian Easter (Meeus/Jones/Butcher computus). */
    private static LocalDate easterSunday(int year) {
        int cycle = year % 19, century = year / 100, yearInCentury = year % 100;
        int lunarCorrection = (century - (century + 8) / 25 + 1) / 3;
        int epact = (19 * cycle + century - century / 4 - lunarCorrection + 15) % 30;
        int weekday = (32 + 2 * (century % 4) + 2 * (yearInCentury / 4) - epact - yearInCentury % 4) % 7;
        int correction = (cycle + 11 * epact + 22 * weekday) / 451;
        int offset = epact + weekday - 7 * correction + 114;
        return LocalDate.of(year, offset / 31, offset % 31 + 1);
    }
}
