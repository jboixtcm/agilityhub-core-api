package com.agilityhub.core.payments.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * S12 R-12-12, the collection-date arithmetic only (the pain.008 file is E8-T03's): by default `billing.sepa.collectionDayOfMonth`
 * read as a day of the **billed** month — `1–28` that day, `0` the month's last day (A28: both semantics are product) — and,
 * when that day is not a TARGET business day (a weekend, 1 January, Good Friday, Easter Monday, 1 May, 25 or 26 December), the
 * next one (SEPA-05, owner decision of 13-11-2026). The date must leave at least two TARGET business days after the generation
 * day (RCUR/CORE), otherwise `422 COLLECTION_DATE_TOO_SOON {requested, earliest}`; the earliest date is itself a business day.
 */
public final class CollectionDates {
    private CollectionDates() { }
    public static final int BUSINESS_DAYS = 2;

    public static LocalDate defaultDate(YearMonth billed, int dayOfMonth) {
        if (dayOfMonth < 0 || dayOfMonth > 28) { throw new IllegalArgumentException("billing.sepa.collectionDayOfMonth"); }
        var day = dayOfMonth == 0 ? billed.atEndOfMonth() : billed.atDay(Math.min(dayOfMonth, billed.lengthOfMonth()));
        return TargetCalendar.onOrAfter(day);
    }
    /** The first acceptable collection date for a remittance generated on {@code generation} (club-local): two TARGET business days after it. */
    public static LocalDate earliest(LocalDate generation) { return TargetCalendar.plusBusinessDays(generation, BUSINESS_DAYS); }
    public static boolean tooSoon(LocalDate requested, LocalDate generation) { return requested.isBefore(earliest(generation)); }
    /** SEPA-05: a collection date the admin chooses must be a TARGET business day. */
    public static boolean businessDay(LocalDate requested) { return TargetCalendar.businessDay(requested); }
}
