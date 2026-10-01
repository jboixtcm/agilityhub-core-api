package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.clubs.census.domain.CancelledBookingType;

/** The parts S13 §3 shares between `InactivityPeriod` and `LeaveRequest`. */
public final class LifecycleParts {
    private LifecycleParts() { }
    /** Who asked: the member's account, or the admin's under impersonation (`impersonatedMemberId` set, `origin = BACKOFFICE`). */
    public record Requester(String accountId, String impersonatedMemberId) { }
    /** A booking R-13-06/R-13-12 cancelled (the trace behind N-18b's and N-28's `cancelled_count`); `sessionDate` is club-local. */
    public record CancelledBooking(CancelledBookingType type, String id, String sessionDate) { }
}
