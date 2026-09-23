package com.agilityhub.core.clubs.bookings.domain;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Objects;

/**
 * S08 R-08-01 booking weeks. `O(t)` is the last club-local occurrence of `bookings.weekOpensAt` ≤ t; a week is
 * `[O, next O)` (167/169 h across DST) and its key is the local date of `O`. Local occurrences follow Java's
 * `ZonedDateTime.of` rules (a gap shifts forward, an overlap takes the earlier offset), as S06 R-06-14 and S15 R-15-02.
 * Nothing here names a weekday: the opening comes from the parameter (T-08-44).
 */
public final class BookingWeeks {
    public record Opening(DayOfWeek day, LocalTime time) {
        public Opening { Objects.requireNonNull(day); Objects.requireNonNull(time); }
        /** The `bookings.weekOpensAt` json value `{dayOfWeek, time}`. */
        public static Opening of(Map<?, ?> value) {
            return new Opening(DayOfWeek.valueOf(value.get("dayOfWeek").toString()), LocalTime.parse(value.get("time").toString()));
        }
    }
    public record Week(String key, Instant start, Instant end) { }

    private final Opening opening;
    private final ZoneId zone;
    public BookingWeeks(Opening opening, ZoneId zone) { this.opening = Objects.requireNonNull(opening); this.zone = Objects.requireNonNull(zone); }
    public ZoneId zone() { return zone; }

    private Instant occurrence(LocalDate date) { return ZonedDateTime.of(date, opening.time(), zone).toInstant(); }

    /** The last local occurrence of the opening at or before {@code now}. */
    public Instant lastOpening(Instant now) {
        var date = now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(opening.day()));
        var candidate = occurrence(date);
        return candidate.isAfter(now) ? occurrence(date.minusWeeks(1)) : candidate;
    }
    /** The booking week containing {@code instant}. */
    public Week week(Instant instant) {
        var start = lastOpening(instant); var date = openingDate(start);
        return new Week(date.toString(), start, occurrence(date.plusWeeks(1)));
    }
    private LocalDate openingDate(Instant start) {
        // The occurrence may have been shifted by a DST gap; its opening date is the matching weekday on or before it.
        return start.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(opening.day()));
    }
    /** CURRENT = W0 (the week of now), NEXT = W1, LATER = W2+ (R-08-01). Earlier weeks count as CURRENT: bookability closes at startsAt. */
    public RelativeWeek relative(Instant classStartsAt, Instant now) {
        var current = week(now);
        if (classStartsAt.isBefore(current.end())) { return RelativeWeek.CURRENT; }
        return classStartsAt.isBefore(week(current.end()).end()) ? RelativeWeek.NEXT : RelativeWeek.LATER;
    }
    /** A week opens for booking one week before it starts, at the local opening occurrence: `start(W) − 7 days`. */
    public Instant opensAt(Week week) { return occurrence(LocalDate.parse(week.key()).minusWeeks(1)); }
    /** Start of the booking week after the class's week (`nextBookableAt` of BOOKING_LIMIT_REACHED). */
    public Instant nextBookableAt(Instant classStartsAt) { return week(classStartsAt).end(); }
}
