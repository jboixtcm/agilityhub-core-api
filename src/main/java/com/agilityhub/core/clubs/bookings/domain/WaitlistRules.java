package com.agilityhub.core.clubs.bookings.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * S08 R-08-12/13/14 waiting-list arithmetic, pure. Joining: live entries of the class &lt; `waitlist.maxPerClass`;
 * live entries of the unit (dog, or owner with `limitUnit = MEMBER`) in the class's booking week &lt;
 * `waitlist.maxPerDogPerWeek`, or &lt; `waitlist.maxPerDogPerWeekIfAttended` once the unit «has already done a class»
 * that week (a counted booking already started or CANCELLED_LATE, part C S08); and the seat must be acceptable:
 * the week is below the booking limit or a swappable booking exists. Offers: ALL_AT_ONCE tells every ACTIVE entry
 * while a seat is free; FIFO keeps as many open offers as free seats; FIFO confirmation window
 * `min(now + waitlist.fifoConfirmMinutes, classStartsAt)`.
 */
public final class WaitlistRules {
    public enum Rejection { CLASS, DOG_WEEK, BOOKING_LIMIT }
    public record Join(int liveOfClass, int maxPerClass, int liveOfUnitInWeek, int maxPerWeek, int maxPerWeekIfAttended, BookingLimits.Result limit) { }
    private WaitlistRules() { }

    public static Optional<Rejection> join(Join j) {
        if (j.liveOfClass() >= j.maxPerClass()) { return Optional.of(Rejection.CLASS); }
        int max = attended(j.limit()) ? j.maxPerWeekIfAttended() : j.maxPerWeek();
        if (j.liveOfUnitInWeek() >= max) { return Optional.of(Rejection.DOG_WEEK); }
        if (j.limit().done()) { return Optional.of(Rejection.BOOKING_LIMIT); }
        return Optional.empty();
    }
    /** «Ja ha fet classe»: a counted booking of the week already started or CANCELLED_LATE (the DONE ones of R-08-09). */
    public static boolean attended(BookingLimits.Result limit) {
        return limit.notSelectable().stream().anyMatch(b -> b.reason() == BookingLimits.Reason.DONE);
    }
    /**
     * How many ACTIVE entries a release or an expiry notifies now.
     * @param freeSeats seats not taken by live bookings
     * @param openOffers entries already NOTIFIED (never notified twice while they stay NOTIFIED)
     * @param waiting ACTIVE entries of the class
     */
    public static int toNotify(WaitlistMode mode, int freeSeats, int openOffers, int waiting) {
        if (freeSeats <= 0) { return 0; }
        return mode == WaitlistMode.ALL_AT_ONCE ? waiting : Math.max(0, Math.min(waiting, freeSeats - openOffers));
    }
    /** R-08-11/14: an offer is only made while strictly more than `waitlist.notifyThresholdMinutes` remain. */
    public static boolean inTime(Instant now, Instant classStartsAt, int notifyThresholdMinutes) {
        return Duration.between(now, classStartsAt).compareTo(Duration.ofMinutes(notifyThresholdMinutes)) > 0;
    }
    public static Instant confirmBy(Instant now, int fifoConfirmMinutes, Instant classStartsAt) {
        var window = now.plus(Duration.ofMinutes(fifoConfirmMinutes));
        return window.isBefore(classStartsAt) ? window : classStartsAt;
    }
}
