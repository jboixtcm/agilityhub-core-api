package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.platform.application.audit.*;
import org.springframework.stereotype.Service;

/**
 * S14 R-14-09 booking actions, written inside the booking transaction. Impersonated (BACKOFFICE) creation and
 * cancellation carry the admin as actor and the member as impersonated; a late cancellation made by anyone else
 * is `BOOKING_CANCELLED_LATE`. One entry per mutation: an impersonated late cancellation is `BY_CLUB` with `late = true`.
 */
@Service
public class BookingAudit {
    @Audited(action = AuditAction.BOOKING_CREATED_BY_CLUB, entityType = "'Booking'", member = "#after.memberId")
    public Booking createdByClub(Booking after) { return after; }
    @Audited(action = AuditAction.BOOKING_CANCELLED_BY_CLUB, entityType = "'Booking'", before = "#before", member = "#after.memberId", reason = "#after.cancelReason")
    public Booking cancelledByClub(Booking before, Booking after) { return after; }
    @Audited(action = AuditAction.BOOKING_CANCELLED_LATE, entityType = "'Booking'", before = "#before", member = "#after.memberId", reason = "#after.cancelReason")
    public Booking cancelledLate(Booking before, Booking after) { return after; }
}
