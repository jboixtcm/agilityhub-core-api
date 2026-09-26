package com.agilityhub.core.clubs.bookings.domain;

import java.time.Instant;

/**
 * S10 R-10-05 «ha avisat», decided at the save instant (§13-5): before `classEndsAt` the booking is cancelled through
 * S08 with its own rules ({@link CancellationPolicy}): `late = now > classStartsAt − bookings.lateCancelThresholdMinutes`
 * (the threshold itself is in time) → CANCELLED_LATE with no refund, otherwise CANCELLED with the pack refunded; the
 * seat is released in both cases, and the waiting list is told only while the class has not started, with WAITLIST on,
 * more than `waitlist.notifyThresholdMinutes` left and live entries (R-08-11). From `classEndsAt` (until `T1`) the
 * booking can no longer be cancelled: `NOTIFIED{afterClassEnd}` is only a record, the booking stays ACTIVE (it counts
 * as done) and nobody is told (§13-4).
 */
public final class NoticeRules {
    private NoticeRules() { }
    /** @param minutesBefore null when `afterClassEnd` */
    public record Outcome(boolean afterClassEnd, boolean late, Integer minutesBefore, BookingState bookingState, boolean packRefunded,
            boolean seatReleased, boolean notifyWaitlist) { }

    public static Outcome decide(Instant classStartsAt, Instant classEndsAt, Instant now, int lateThresholdMinutes, boolean waitlistModule,
            int notifyThresholdMinutes, boolean liveEntries, boolean paidWithPack) {
        if (!now.isBefore(classEndsAt)) { return new Outcome(true, true, null, BookingState.ACTIVE, false, false, false); }
        var outcome = CancellationPolicy.evaluate(classStartsAt, now, lateThresholdMinutes);
        boolean notify = now.isBefore(classStartsAt) && CancellationPolicy.notifyWaitlist(waitlistModule, classStartsAt, now, notifyThresholdMinutes, liveEntries);
        return new Outcome(false, outcome.late(), outcome.minutesBefore(), outcome.late() ? BookingState.CANCELLED_LATE : BookingState.CANCELLED,
                !outcome.late() && paidWithPack, true, notify);
    }
}
