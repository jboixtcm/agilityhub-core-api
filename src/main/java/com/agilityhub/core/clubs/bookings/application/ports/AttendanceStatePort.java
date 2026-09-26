package com.agilityhub.core.clubs.bookings.application.ports;

import java.util.Optional;

/** S10 R-10-03 contract: the attendance of a booking (`PRESENT` · `NO_SHOW` · …), served by `AttendanceStates` (E6-T02). */
public interface AttendanceStatePort {
    Optional<String> state(String bookingId);
    /** A PRESENT/NO_SHOW mark makes the booking no longer cancellable (R-08-10). */
    default boolean marked(String bookingId) { return state(bookingId).filter(s -> s.equals("PRESENT") || s.equals("NO_SHOW")).isPresent(); }
    default boolean noShow(String bookingId) { return state(bookingId).filter("NO_SHOW"::equals).isPresent(); }
}
