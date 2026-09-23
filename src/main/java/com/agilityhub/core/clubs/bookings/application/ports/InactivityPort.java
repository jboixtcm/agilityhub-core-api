package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.LocalDate;
import java.util.Optional;

/**
 * S08 R-08-06 (BR-16): the owner's APPROVED/ACTIVE `InactivityPeriod` covering a club-local date. S13 WP-13-B
 * (E8, decision B11) supplies the real adapter; until then the default has no periods and the local/test profiles
 * use {@link InMemoryInactivity}.
 */
public interface InactivityPort {
    record Period(LocalDate from, LocalDate to) { }
    Optional<Period> covering(String memberId, LocalDate date);
}
