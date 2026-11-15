package com.agilityhub.core.payments.domain;

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
        return TargetCalendar.plusBusinessDays(following(generation), days);
    }
    public static LocalDate following(LocalDate date) { return TargetCalendar.onOrAfter(date); }
    /** The first acceptable collection date for a remittance generated on {@code generation} (club-local): two TARGET business days after it. */
    public static LocalDate earliest(LocalDate generation) { return earliest(generation, 2); }
    public static boolean tooSoon(LocalDate requested, LocalDate generation) { return requested.isBefore(earliest(generation)); }
    /** SEPA-05: a collection date the admin chooses must be a TARGET business day. */
    public static boolean businessDay(LocalDate requested) { return TargetCalendar.businessDay(requested); }
}
