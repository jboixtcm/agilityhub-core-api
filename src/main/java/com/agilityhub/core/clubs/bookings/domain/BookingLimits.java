package com.agilityhub.core.clubs.bookings.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * S08 R-08-02/R-08-03/R-08-09 weekly limit of one booking week. Counted: ACTIVE, PAYMENT_PENDING, CANCELLED_LATE.
 * `DOG` counts the dog's bookings whoever made them; `MEMBER` counts every booking of the dog's owner
 * (`Booking.memberId`). Swappable = counted ACTIVE bookings still cancellable in time
 * (`classStartsAt − now ≥ bookings.lateCancelThresholdMinutes`); the other counted ones are not selectable:
 * DONE (started or CANCELLED_LATE) or LATE_WINDOW (inside the threshold, also a PAYMENT_PENDING booking).
 */
public final class BookingLimits {
    public static final Set<BookingState> COUNTED = EnumSet.of(BookingState.ACTIVE, BookingState.PAYMENT_PENDING, BookingState.CANCELLED_LATE);
    public enum Reason { DONE, LATE_WINDOW }
    public record Counted(String bookingId, String classSessionId, String dogId, String memberId, BookingState state, Instant classStartsAt) { }
    public record Blocked(Counted booking, Reason reason) { }
    public record Result(boolean reached, LimitUnit unit, int count, int max, List<Counted> swappable, List<Blocked> notSelectable) {
        public Result { swappable = List.copyOf(swappable); notSelectable = List.copyOf(notSelectable); }
        /** Limit reached and nothing to swap: the informative path (409 BOOKING_LIMIT_REACHED, row WEEKLY_LIMIT_DONE). */
        public boolean done() { return reached && swappable.isEmpty(); }
        public boolean canSwap(String bookingId) { return swappable.stream().anyMatch(b -> b.bookingId().equals(bookingId)); }
    }
    private BookingLimits() { }

    /** @param weekBookings the bookings of the class's booking week (any state, any dog of the club) */
    public static Result evaluate(Collection<Counted> weekBookings, LimitUnit unit, String dogId, String ownerMemberId, int max,
            Instant now, Duration lateThreshold) {
        var counted = weekBookings.stream().filter(b -> COUNTED.contains(b.state()))
                .filter(b -> unit == LimitUnit.DOG ? b.dogId().equals(dogId) : b.memberId().equals(ownerMemberId))
                .sorted(Comparator.comparing(Counted::classStartsAt).thenComparing(Counted::bookingId)).toList();
        var swappable = new ArrayList<Counted>(); var blocked = new ArrayList<Blocked>();
        for (var b : counted) {
            if (b.state() == BookingState.CANCELLED_LATE || !b.classStartsAt().isAfter(now)) { blocked.add(new Blocked(b, Reason.DONE)); }
            else if (b.state() == BookingState.ACTIVE && !now.isAfter(b.classStartsAt().minus(lateThreshold))) { swappable.add(b); }
            else { blocked.add(new Blocked(b, Reason.LATE_WINDOW)); }
        }
        return new Result(counted.size() >= max, unit, counted.size(), max, swappable, blocked);
    }
    /** Max of the class's week: `bookings.maxCurrentWeek` for W0, `bookings.maxNextWeek` for W1; W2+ is not bookable yet. */
    public static int max(RelativeWeek week, int maxCurrentWeek, int maxNextWeek) {
        return week == RelativeWeek.CURRENT ? maxCurrentWeek : maxNextWeek;
    }
}
