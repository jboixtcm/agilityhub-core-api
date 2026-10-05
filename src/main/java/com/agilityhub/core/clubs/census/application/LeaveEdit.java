package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.leave.LeaveRequest;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts.*;
import java.time.Instant;
import java.util.List;

/** Mutable transaction-local draft; persistence remains an immutable versioned snapshot. */
final class LeaveEdit {
    String id;
    String clubId;
    String memberId;
    LeaveSource source;
    LifecycleOrigin origin;
    Instant requestedAt;
    Requester requestedBy;
    String requestedDate;
    String reasonKey;
    Integer nps;
    String comment;
    LeaveRequestState state;
    LeaveRequest.Decision decision;
    Instant executedAt;
    Instant cancelledAt;
    LifecycleCanceller cancelledBy;
    LeaveCancelReason cancelReason;
    List<CancelledBooking> cancelledBookings;
    String packBalanceId;
    Long version;
    Instant createdAt;
    Instant updatedAt;
    LeaveEdit() { }
    LeaveEdit(LeaveRequest p) {
        id = p.id();
        clubId = p.clubId();
        memberId = p.memberId();
        source = p.source();
        origin = p.origin();
        requestedAt = p.requestedAt();
        requestedBy = p.requestedBy();
        requestedDate = p.requestedDate();
        reasonKey = p.reasonKey();
        nps = p.nps();
        comment = p.comment();
        state = p.state();
        decision = p.decision();
        executedAt = p.executedAt();
        cancelledAt = p.cancelledAt();
        cancelledBy = p.cancelledBy();
        cancelReason = p.cancelReason();
        cancelledBookings = p.cancelledBookings();
        packBalanceId = p.packBalanceId();
        version = p.version();
        createdAt = p.createdAt();
        updatedAt = p.updatedAt();
    }
    LeaveRequest snapshot() { return new LeaveRequest(id, clubId, memberId, source, origin, requestedAt, requestedBy, requestedDate, reasonKey, nps, comment, state, decision, executedAt, cancelledAt, cancelledBy, cancelReason, cancelledBookings, packBalanceId, version, createdAt, updatedAt); }
}
