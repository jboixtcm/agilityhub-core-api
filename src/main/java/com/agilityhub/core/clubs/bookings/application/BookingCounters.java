package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess.LowAlert;
import com.agilityhub.core.platform.application.Module;
import org.springframework.stereotype.Service;

/**
 * `ClassSession.counters` follow the bookings in the same transaction (E4-T03 contract): `booked` = ACTIVE +
 * PAYMENT_PENDING, `waiting` = live entries (0 with WAITLIST off). S15 R-15-12b: an in-time cancellation that leaves an ACTIVE, future,
 * non-exempt class below `classes.minDogs` emits `ClassBelowMinimum` once, guarded by `risk.lowAlertSentAt`,
 * which is cleared when the count reaches the minimum again (so a second drop alerts again).
 */
@Service
public class BookingCounters {
    private final BookingContext context; private final ClassSessionBookingAccess classes; private final BookingRepository bookings;
    private final WaitlistEntryRepository waitlist; private final BookingEvents events;
    public BookingCounters(BookingContext context, ClassSessionBookingAccess classes, BookingRepository bookings, WaitlistEntryRepository waitlist, BookingEvents events) {
        this.context = context; this.classes = classes; this.bookings = bookings; this.waitlist = waitlist; this.events = events;
    }
    /**
     * @param alertIfBelowMinimum the two R-15-12b triggers: a `BookingCancelled{late: false}` of this class happened in
     *        the transaction, or a `WaitlistExpired` (P6) left it unchanged. A P6 expiry alerts whenever the class is
     *        below the minimum with no standing alert, even without a drop (E5-T10).
     */
    public void recount(String classSessionId, boolean alertIfBelowMinimum, BookingActor actor) {
        var session = classes.find(classSessionId).orElse(null);
        if (session == null || !session.active()) { return; }
        int booked = bookings.forClass(classSessionId, BookingRepository.LIVE).size();
        int waiting = context.enabled(Module.WAITLIST) ? waitlist.live(classSessionId).size() : 0; // S08 §9; recounted when WAITLIST is back on (BookingConsumers)
        int minDogs = context.integer("classes.minDogs"); var alert = LowAlert.KEEP;
        if (booked >= minDogs && session.lowAlertSentAt() != null) { alert = LowAlert.CLEAR; }
        else if (alertIfBelowMinimum && booked < minDogs && session.lowAlertSentAt() == null && !session.riskExempt()
                && context.now().isBefore(session.startsAt())) {
            alert = LowAlert.SET; events.belowMinimum(classSessionId, booked, minDogs, actor);
        }
        // Always written: the class_sessions write is what makes a booking conflict with a concurrent S06 cancellation.
        classes.counters(classSessionId, booked, waiting, alert);
    }
}
