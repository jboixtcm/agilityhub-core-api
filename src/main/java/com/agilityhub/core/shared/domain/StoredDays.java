package com.agilityhub.core.shared.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;

/**
 * INC-68 (E11-T08): one rule to read a business day stored as `YYYY-MM-DD` or, before the fix, as a BSON date. Such a date
 * is the writer JVM's local midnight (Spring Data's `LocalDate -> Date`), whatever that JVM's zone was; the day is the UTC
 * day of the nearest UTC midnight. The rule does not depend on the zone of the JVM that reads, and gives the written day
 * for every writer zone from UTC-11:59 to UTC+12:00.
 */
public final class StoredDays {
    private static final Duration HALF_DAY = Duration.ofHours(12);

    private StoredDays() { }

    /** The day of a stored value; {@code null} stays {@code null}. */
    public static LocalDate day(Object stored) {
        if (stored == null) { return null; }
        if (stored instanceof Date date) { return date.toInstant().plus(HALF_DAY).atZone(ZoneOffset.UTC).toLocalDate(); }
        return LocalDate.parse(stored.toString());
    }
}
