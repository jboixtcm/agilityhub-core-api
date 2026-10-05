package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriod;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts.*;
import java.time.Instant;
import java.util.List;

/** Mutable transaction-local draft; persistence remains an immutable versioned snapshot. */
final class InactivityEdit {
    String id;
    String clubId;
    String memberId;
    String fromMonth;
    String toMonth;
    String comments;
    InactivityState state;
    LifecycleOrigin origin;
    Instant requestedAt;
    Requester requestedBy;
    InactivityPeriod.Decision decision;
    InactivityPeriod.FeeSnapshot feeSnapshot;
    Instant startedAt;
    Instant finishedAt;
    InactivityFinishReason finishReason;
    Instant cancelledAt;
    LifecycleCanceller cancelledBy;
    InactivityCancelReason cancelReason;
    List<CancelledBooking> cancelledBookings;
    List<InactivityPeriod.HistoryEntry> history;
    Long version;
    Instant createdAt;
    Instant updatedAt;
    InactivityEdit() { }
    InactivityEdit(InactivityPeriod p) {
        id = p.id();
        clubId = p.clubId();
        memberId = p.memberId();
        fromMonth = p.fromMonth();
        toMonth = p.toMonth();
        comments = p.comments();
        state = p.state();
        origin = p.origin();
        requestedAt = p.requestedAt();
        requestedBy = p.requestedBy();
        decision = p.decision();
        feeSnapshot = p.feeSnapshot();
        startedAt = p.startedAt();
        finishedAt = p.finishedAt();
        finishReason = p.finishReason();
        cancelledAt = p.cancelledAt();
        cancelledBy = p.cancelledBy();
        cancelReason = p.cancelReason();
        cancelledBookings = p.cancelledBookings();
        history = p.history();
        version = p.version();
        createdAt = p.createdAt();
        updatedAt = p.updatedAt();
    }
    InactivityPeriod snapshot() { return new InactivityPeriod(id, clubId, memberId, fromMonth, toMonth, comments, state, origin, requestedAt, requestedBy, decision, feeSnapshot, startedAt, finishedAt, finishReason, cancelledAt, cancelledBy, cancelReason, cancelledBookings, history, version, createdAt, updatedAt); }
}
