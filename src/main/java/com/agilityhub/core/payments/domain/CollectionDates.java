package com.agilityhub.core.payments.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * S12 R-12-12, the collection-date arithmetic only (the pain.008 file is E8-T03's): by default `billing.sepa.collectionDayOfMonth`
 * read as a day of the **billed** month — `1–28` that day, `0` the month's last day (A28: both semantics are product). The
 * date must leave at least two business days (Monday to Friday) after the generation day (RCUR/CORE), otherwise
 * `422 COLLECTION_DATE_TOO_SOON {requested, earliest}`.
 */
public final class CollectionDates {
    private CollectionDates() { }
    public static final int BUSINESS_DAYS = 2;

    public static LocalDate defaultDate(YearMonth billed, int dayOfMonth) {
        if (dayOfMonth < 0 || dayOfMonth > 28) { throw new IllegalArgumentException("billing.sepa.collectionDayOfMonth"); }
        return dayOfMonth == 0 ? billed.atEndOfMonth() : billed.atDay(Math.min(dayOfMonth, billed.lengthOfMonth()));
    }
    /** The first acceptable collection date for a remittance generated on {@code generation} (club-local). */
    public static LocalDate earliest(LocalDate generation) {
        LocalDate day = generation; int counted = 0;
        while (counted < BUSINESS_DAYS) {
            day = day.plusDays(1);
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) { counted++; }
        }
        return day;
    }
    public static boolean tooSoon(LocalDate requested, LocalDate generation) { return requested.isBefore(earliest(generation)); }
}
