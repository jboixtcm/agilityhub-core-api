package com.agilityhub.core.clubs.bookings.domain;

import java.time.Instant;

/** S08 §6 `displayState` of screen 07: confirmada · feta · no presentat · anul·lada · anul·lada tard · cancel·lada pel club · pendent de pagament. */
public final class BookingDisplay {
    public enum State { CONFIRMED, DONE, NO_SHOW, CANCELLED, CANCELLED_LATE, CANCELLED_BY_CLUB, PAYMENT_PENDING }
    private BookingDisplay() { }
    /** @param noShow the S10 attendance is NO_SHOW (E6; always false until then) */
    public static State of(BookingState state, Instant classEndsAt, Instant now, boolean noShow) {
        return switch (state) {
            case PAYMENT_PENDING -> State.PAYMENT_PENDING;
            case CANCELLED -> State.CANCELLED;
            case CANCELLED_LATE -> State.CANCELLED_LATE;
            case CANCELLED_BY_CLUB -> State.CANCELLED_BY_CLUB;
            case ACTIVE -> noShow ? State.NO_SHOW : classEndsAt.isAfter(now) ? State.CONFIRMED : State.DONE;
        };
    }
}
