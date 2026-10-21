package com.agilityhub.core.shared.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;

/**
 * INC-68 (E11-T08): one rule to read a business day stored as `YYYY-MM-DD` or, before the fix, as a BSON date. Such a date
 * is the writer JVM's local midnight (Spring Data's `LocalDate -> Date`), whatever that JVM's zone was; the day is the UTC
 * day of the nearest UTC midnight. The rule does not depend on the zone of the JVM that reads. INC-74 (E11-T13): it depends
 * on the writer's UTC offset at that midnight, not on its zone, and gives the written day for every writer offset from
 * UTC-11:59 to UTC+12:00; a writer at UTC+13 or UTC+14 (Tonga, or New Zealand in its summer) reads a day early.
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
