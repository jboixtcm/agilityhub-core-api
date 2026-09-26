package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.AttendanceStatePort;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * S10 → S08 `AttendanceStatePort` (E6-T02): the stored attendance of a booking. S08 reads it for `displayState`
 * «no presentat» (07) and for the R-08-10 precondition: a booking marked PRESENT or NO_SHOW is no longer cancellable
 * (`422 BOOKING_NOT_CANCELLABLE`, S10 §13-6 while Jordi answers).
 */
@Service
public class AttendanceStates implements AttendanceStatePort {
    private final AttendanceRepository attendances;
    public AttendanceStates(AttendanceRepository attendances) { this.attendances = attendances; }
    @Override public Optional<String> state(String bookingId) { return attendances.stateOf(bookingId).map(Enum::name); }
}
