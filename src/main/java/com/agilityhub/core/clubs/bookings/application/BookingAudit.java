package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.platform.application.audit.*;
import org.springframework.stereotype.Service;

/**
 * S14 R-14-09 booking actions, written inside the booking transaction. Impersonated (BACKOFFICE) creation and
 * cancellation carry the admin as actor and the member as impersonated; every late cancellation is also
 * `BOOKING_CANCELLED_LATE`, so an impersonated late cancellation writes both entries (`BY_CLUB` and `LATE`).
 */
@Service
public class BookingAudit {
    /** COMM-03: the entry of a club action that did not notify the member says so (`details.notifyMember = false`). */
    static final String SILENCED = "#notify ? null : {notifyMember: false}";
    @Audited(action = AuditAction.BOOKING_CREATED_BY_CLUB, entityType = "'Booking'", member = "#after.memberId", details = SILENCED)
    public Booking createdByClub(Booking after, boolean notify) { return after; }
    @Audited(action = AuditAction.BOOKING_CANCELLED_BY_CLUB, entityType = "'Booking'", before = "#before", member = "#after.memberId", reason = "#after.cancelReason",
            details = SILENCED)
    public Booking cancelledByClub(Booking before, Booking after, boolean notify) { return after; }
    @Audited(action = AuditAction.BOOKING_CANCELLED_LATE, entityType = "'Booking'", before = "#before", member = "#after.memberId", reason = "#after.cancelReason")
    public Booking cancelledLate(Booking before, Booking after) { return after; }
}
