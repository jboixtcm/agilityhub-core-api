package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;

/**
 * The staff member behind an S10 request (21, 20, D12, 22/D13): the account, the name written in `markedBy` and
 * `savedByName` (the instructor's `shortName` when the caller has a profile, R-10-01), the ADMIN role (R-10-03: marks
 * outside the window, audited) and the caller's own `Instructor` (the default filter of 20 and «Els meus» of D12).
 */
public record AttendanceCaller(String accountId, String displayName, boolean admin, String instructorId) {
    public ActorRole role() { return admin ? ActorRole.ADMIN : ActorRole.INSTRUCTOR; }
    /** R-08-19: the «ha avisat» cancellation is `origin = INSTRUCTOR` whoever saves the sheet; `cancelledBy.role` is the caller's. */
    public BookingActor noticeActor() { return new BookingActor(accountId, null, displayName, null, BookingOrigin.INSTRUCTOR, role()); }
}
