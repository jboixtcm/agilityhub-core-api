package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts.*;
import com.agilityhub.core.clubs.census.persistence.leave.*;
import com.agilityhub.core.clubs.census.persistence.inactivity.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** S13 R-13-09/10/12/15: a decision fixes a date; only the scheduler changes ACTIVE to LEFT. */
@Service
public class LeaveRequestService {
    private final LeaveRequestRepository requests; private final InactivityPeriodRepository periods; private final CensusAccess census;
    private final LifecycleBookings bookings; private final InactivityPeriodService inactivity; private final CensusEvents events;
    private final MemberStatusService statuses; private final Clock clock; private final ClubClock local;
    public LeaveRequestService(LeaveRequestRepository requests, InactivityPeriodRepository periods, CensusAccess census, LifecycleBookings bookings,
            InactivityPeriodService inactivity, CensusEvents events, MemberStatusService statuses, Clock clock, ClubClock local) {
        this.requests = requests; this.periods = periods; this.census = census; this.bookings = bookings; this.inactivity = inactivity;
        this.events = events; this.statuses = statuses; this.clock = clock; this.local = local;
    }
    public LeaveRequest get(String id) { return requests.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public List<LeaveRequest> ofMember(String id) { return requests.ofMember(id); }
    private LocalDate today() { return local.today(TenantContext.require()); }
    private Member active(String id) {
        var member = census.mutableMember(id);
        if (!"ACTIVE".equals(member.status)) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
        if (member.leaveDate != null) { throw new ApiException(ErrorCode.LEAVE_ALREADY_SCHEDULED); }
        return member;
    }
    private void date(LocalDate date) { if (date.isBefore(today())) { throw new ApiException(ErrorCode.LEAVE_DATE_INVALID); } }
    public void reason(String key, boolean admin) {
        if (key == null && admin) { return; }
        boolean allowed = ((List<?>) census.config().get("leave.reasons", List.class)).stream().map(CensusValues::map)
                .anyMatch(r -> Objects.equals(key, r.get("key")) && (admin || !Set.of("CLUB_DECISION", "PACK_EXPIRED").contains(key)));
        if (!allowed) { throw new ApiException(ErrorCode.LEAVE_REASON_UNKNOWN); }
    }
    private LeaveEdit draft(String memberId, LocalDate date, String reason, LeaveSource source) {
        var e = new LeaveEdit(); var user = CurrentUser.current(); e.id = UUID.randomUUID().toString(); e.clubId = TenantContext.require(); e.memberId = memberId;
        e.source = source; e.origin = source == LeaveSource.PACK_EXPIRED ? LifecycleOrigin.SYSTEM
                : source == LeaveSource.ADMIN || user != null && user.impersonation() != null ? LifecycleOrigin.BACKOFFICE : LifecycleOrigin.APP;
        e.requestedAt = clock.instant(); e.requestedBy = InactivityPeriodService.requester(); e.requestedDate = date.toString(); e.reasonKey = reason;
        e.state = LeaveRequestState.PENDING; e.cancelledBookings = List.of(); e.createdAt = clock.instant(); e.updatedAt = clock.instant(); return e;
    }
    @Transactional
    @Audited(action = AuditAction.LEAVE_RESOLVED, entityType = "'LeaveRequest'", entity = "#result.id", member = "#memberId")
    public LeaveRequest request(String memberId, LocalDate requestedDate, String reasonKey, Integer nps, String comment) {
        census.members.lock(); active(memberId);
        if (requests.ofMember(memberId).stream().anyMatch(r -> r.state() == LeaveRequestState.PENDING)) { throw new ApiException(ErrorCode.LEAVE_ALREADY_REQUESTED); }
        date(requestedDate); reason(reasonKey, false);
        if (nps != null && !census.config().get("leave.npsEnabled", Boolean.class)) { throw new ApiException(ErrorCode.READ_ONLY); }
        var e = draft(memberId, requestedDate, reasonKey, LeaveSource.MEMBER); e.nps = nps; e.comment = comment;
        var result = requests.insert(e.snapshot()); emit("LeaveRequested", result, Map.of()); return result;
    }
    @Transactional
    @Audited(action = AuditAction.LEAVE_RESOLVED, entityType = "'LeaveRequest'", entity = "#id")
    public LeaveRequest decide(String id, LifecycleDecision decision, LocalDate effectiveDate, String note) {
        census.members.lock(); var old = get(id);
        if (old.state() != LeaveRequestState.PENDING) { throw new ApiException(ErrorCode.LEAVE_INVALID_STATE); }
        var e = new LeaveEdit(old);
        if (decision == LifecycleDecision.APPROVED) {
            var effective = effectiveDate == null ? LocalDate.parse(e.requestedDate) : effectiveDate; date(effective); approve(e, active(e.memberId), effective, note);
        } else { e.state = LeaveRequestState.DENIED; e.decision = new LeaveRequest.Decision(clock.instant(), InactivityPeriodService.actor(), decision, null, note); }
        var result = save(e, old.version()); emit("LeaveResolved", result, Map.of("decision", decision)); return result;
    }
    @Transactional
    @Audited(action = AuditAction.LEAVE_RESOLVED, entityType = "'LeaveRequest'", entity = "#result.id", member = "#memberId")
    public LeaveRequest direct(String memberId, LocalDate effectiveDate, String reasonKey, String note) {
        census.members.lock(); var member = active(memberId); date(effectiveDate); reason(reasonKey, true);
        for (var pending : requests.ofMember(memberId)) {
            if (pending.state() == LeaveRequestState.PENDING) { cancelRequest(pending, LifecycleCanceller.ADMIN, LeaveCancelReason.ADMIN); }
        }
        var e = draft(memberId, effectiveDate, reasonKey, LeaveSource.ADMIN); approve(e, member, effectiveDate, note);
        var result = requests.insert(e.snapshot()); emit("LeaveResolved", result, Map.of("decision", LifecycleDecision.APPROVED)); return result;
    }
    private void approve(LeaveEdit e, Member member, LocalDate effective, String note) {
        e.state = LeaveRequestState.APPROVED; e.decision = new LeaveRequest.Decision(clock.instant(), InactivityPeriodService.actor(), LifecycleDecision.APPROVED, effective.toString(), note);
        member.leaveDate = effective; member.leaveRequestId = e.id; census.members.save(member);
        e.cancelledBookings = sweep(e.memberId, effective); closePeriods(e.memberId, effective, false);
    }
    List<CancelledBooking> sweep(String memberId, LocalDate effective) {
        return bookings.inside(memberId, effective.plusDays(1), null, true, true).stream()
                .map(b -> new CancelledBooking(CancelledBookingType.valueOf(b.type()), b.id(), b.sessionDate().toString())).toList();
    }
    void closePeriods(String memberId, LocalDate effective, boolean execute) {
        for (var p : periods.ofMember(memberId)) {
            var e = new InactivityEdit(p);
            if (p.state() == InactivityState.ACTIVE) {
                e.toMonth = YearMonth.from(effective).toString(); e.finishReason = InactivityFinishReason.LEAVE;
                if (execute) { e.state = InactivityState.FINISHED; e.finishedAt = clock.instant(); }
            } else if (p.state() == InactivityState.REQUESTED || p.state() == InactivityState.APPROVED) {
                inactivity.cancelled(e, LifecycleCanceller.SYSTEM, InactivityCancelReason.LEAVE);
            } else { continue; }
            inactivity.save(e, p.version());
            if (e.state == InactivityState.CANCELLED) { inactivity.emit("InactivityCancelled", e.snapshot(), Map.of("by", LifecycleCanceller.SYSTEM, "reason", InactivityCancelReason.LEAVE)); }
        }
    }
    @Transactional
    @Audited(action = AuditAction.LEAVE_CANCELLED, entityType = "'LeaveRequest'", entity = "#id")
    public LeaveRequest withdraw(String id) {
        census.members.lock(); var old = get(id);
        if (old.state() == LeaveRequestState.CANCELLED) { return old; }
        if (old.state() != LeaveRequestState.PENDING) { throw new ApiException(ErrorCode.LEAVE_INVALID_STATE); }
        return cancelRequest(old, LifecycleCanceller.MEMBER, LeaveCancelReason.WITHDRAWN);
    }
    @Transactional
    @Audited(action = AuditAction.LEAVE_CANCELLED, entityType = "'Member'", entity = "#memberId", member = "#memberId")
    public void cancelPlanned(String memberId) { cancelPlanned(memberId, false); }
    void cancelPlanned(String memberId, boolean renewed) {
        census.members.lock(); var member = census.mutableMember(memberId);
        if (!"ACTIVE".equals(member.status) || member.leaveDate == null) { throw new ApiException(ErrorCode.NO_PLANNED_LEAVE); }
        if (member.leaveRequestId != null) { cancelRequest(get(member.leaveRequestId), renewed ? LifecycleCanceller.SYSTEM : LifecycleCanceller.ADMIN, renewed ? LeaveCancelReason.PACK_RENEWED : LeaveCancelReason.ADMIN); }
        member.leaveDate = null; member.leaveRequestId = null; census.members.save(member);
    }
    private LeaveRequest cancelRequest(LeaveRequest old, LifecycleCanceller by, LeaveCancelReason reason) {
        var e = new LeaveEdit(old); e.state = LeaveRequestState.CANCELLED; e.cancelledAt = clock.instant(); e.cancelledBy = by; e.cancelReason = reason;
        var result = save(e, old.version()); emit("LeaveCancelled", result, Map.of("by", by, "reason", reason)); return result;
    }
    @Transactional
    @Audited(action = AuditAction.MEMBER_STATUS_CHANGED, entityType = "'Member'", entity = "#id", member = "#id")
    public void reactivate(String id, String planId, String priceId, LocalDate nextInvoiceDate) {
        // Resolve references first, even when the member's state or billing input would fail later (E88).
        if (planId != null && census.references.plan(planId).isEmpty() || priceId != null && census.references.price(priceId).isEmpty()) { throw new ApiException(ErrorCode.NOT_FOUND); }
        census.members.lock(); var member = census.mutableMember(id);
        if (!"LEFT".equals(member.status)) { throw new ApiException(ErrorCode.MEMBER_NOT_LEFT); }
        if (census.enabled(Module.BILLING) && (planId == null || priceId == null || nextInvoiceDate == null)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        if (planId != null && priceId != null && !planId.equals(census.references.price(priceId).get("planId"))) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        var history = new ArrayList<>(member.leaveHistory == null ? List.<Map<String, Object>>of() : member.leaveHistory);
        history.add(object("leaveDate", member.leaveDate == null ? null : member.leaveDate.toString(), "leftAt", member.leftAt, "leftReason", member.leftReason, "reactivatedAt", clock.instant())); member.leaveHistory = List.copyOf(history);
        member.leftAt = null; member.leftReason = null;
        if (census.enabled(Module.BILLING)) { member.planId = planId; member.priceId = priceId; member.nextInvoiceDate = nextInvoiceDate; }
        census.members.save(member); statuses.transition(id, "ACTIVE", today(), null);
    }
    LeaveRequest save(LeaveEdit e, Long expected) { e.version = expected + 1; e.updatedAt = clock.instant(); return requests.save(e.snapshot(), expected); }
    void emit(String type, LeaveRequest r, Map<String, Object> extra) {
        var payload = object("requestId", r.id(), "memberId", r.memberId(), "origin", r.origin(), "source", r.source(), "requestedDate", r.requestedDate(),
                "reasonKey", r.reasonKey(), "effectiveDate", r.decision() == null ? null : r.decision().effectiveDate(), "cancelledBookings", r.cancelledBookings(),
                "admin_text", r.decision() == null ? null : r.decision().note()); payload.putAll(extra); events.emit(type, "LeaveRequest", r.id(), payload);
    }
}
