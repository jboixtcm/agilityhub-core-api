package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.inactivity.domain.InactivityCalendar;
import com.agilityhub.core.clubs.census.persistence.inactivity.*;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** S13 R-13-02 through R-13-06. Every public mutation joins its caller's transaction. */
@Service
public class InactivityPeriodService {
    private static final Set<InactivityState> LIVE = Set.of(InactivityState.REQUESTED, InactivityState.APPROVED, InactivityState.ACTIVE);
    private final InactivityPeriodRepository periods; private final CensusAccess census; private final InactivityFeeService fees;
    private final LifecycleBookings bookings; private final CensusEvents events; private final Clock clock; private final ClubClock local;
    public InactivityPeriodService(InactivityPeriodRepository periods, CensusAccess census, InactivityFeeService fees,
            LifecycleBookings bookings, CensusEvents events, Clock clock, ClubClock local) {
        this.periods = periods; this.census = census; this.fees = fees; this.bookings = bookings; this.events = events; this.clock = clock; this.local = local;
    }
    public YearMonth earliest() { return InactivityCalendar.earliest(local.today(TenantContext.require()), census.config().get("inactivity.requestDeadlineDay", Integer.class)); }
    public InactivityPeriod get(String id) { return periods.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public List<InactivityPeriod> ofMember(String id) { return periods.ofMember(id); }
    private YearMonth month() { return YearMonth.from(local.today(TenantContext.require())); }
    private static YearMonth ym(String value) { return value == null ? null : YearMonth.parse(value); }
    private void validMember(String id) {
        var member = census.mutableMember(id);
        if (!"ACTIVE".equals(member.status)) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
        if (member.leaveDate != null) { throw new ApiException(ErrorCode.LEAVE_ALREADY_SCHEDULED); }
        if (!"MONTHLY".equals(census.references.plan(member.planId).get("type"))) { throw new ApiException(ErrorCode.INACTIVITY_NOT_APPLICABLE); }
    }
    private void overlap(String memberId, String except, String from, String to) {
        for (var p : periods.ofMember(memberId)) {
            if (!p.id().equals(except) && LIVE.contains(p.state()) && InactivityCalendar.overlapsOrAdjacent(ym(from), ym(to), ym(p.fromMonth()), ym(p.toMonth()))) {
                throw new ApiException(ErrorCode.INACTIVITY_OVERLAP, Map.of("periodId", p.id(), "hint", "EXTEND"));
            }
        }
    }
    @Transactional
    @Audited(action = AuditAction.INACTIVITY_RESOLVED, entityType = "'InactivityPeriod'", entity = "#result.id", member = "#memberId")
    public InactivityPeriod request(String memberId, String from, String to, String comments, boolean admin, boolean override) {
        census.require(Module.INACTIVITY); census.members.lock(); validMember(memberId);
        InactivityCalendar.request(ym(from), ym(to), earliest(), census.config().get("inactivity.maxStartMonthsAhead", Integer.class), override);
        overlap(memberId, null, from, to);
        var e = new InactivityEdit(); var user = CurrentUser.current();
        e.id = UUID.randomUUID().toString(); e.clubId = TenantContext.require(); e.memberId = memberId; e.fromMonth = from; e.toMonth = to;
        e.comments = comments; e.state = InactivityState.REQUESTED; e.origin = admin || user != null && user.impersonation() != null ? LifecycleOrigin.BACKOFFICE : LifecycleOrigin.APP;
        e.requestedAt = clock.instant(); e.requestedBy = requester(); e.createdAt = clock.instant(); e.updatedAt = clock.instant();
        e.cancelledBookings = List.of(); e.history = List.of();
        if (admin) { approve(e, null, override); }
        var result = periods.insert(e.snapshot());
        emit(admin ? "InactivityResolved" : "InactivityRequested", result, admin ? Map.of("decision", "APPROVED") : Map.of());
        if (result.state() == InactivityState.ACTIVE) { emit("InactivityStarted", result, Map.of()); }
        return result;
    }
    @Transactional
    @Audited(action = AuditAction.INACTIVITY_RESOLVED, entityType = "'InactivityPeriod'", entity = "#id")
    public InactivityPeriod decide(String id, LifecycleDecision decision, String note) {
        census.require(Module.INACTIVITY); census.members.lock(); var old = get(id);
        if (old.state() != InactivityState.REQUESTED) { throw new ApiException(ErrorCode.INACTIVITY_INVALID_STATE); }
        var e = new InactivityEdit(old);
        if (decision == LifecycleDecision.APPROVED) { validMember(old.memberId()); approve(e, note, false); }
        else { e.state = InactivityState.DENIED; e.decision = new InactivityPeriod.Decision(clock.instant(), actor(), decision, note, false); }
        var result = save(e, old.version()); emit("InactivityResolved", result, Map.of("decision", decision.name()));
        if (result.state() == InactivityState.ACTIVE) { emit("InactivityStarted", result, Map.of()); }
        return result;
    }
    private void approve(InactivityEdit e, String note, boolean override) {
        e.state = InactivityCalendar.covers(ym(e.fromMonth), ym(e.toMonth), month()) ? InactivityState.ACTIVE : InactivityState.APPROVED;
        e.feeSnapshot = fees.snapshot(); e.decision = new InactivityPeriod.Decision(clock.instant(), actor(), LifecycleDecision.APPROVED, note, override);
        if (e.state == InactivityState.ACTIVE) { e.startedAt = clock.instant(); }
        e.cancelledBookings = cancelInside(e.memberId, e.fromMonth, e.toMonth);
    }
    private List<CancelledBooking> cancelInside(String member, String from, String to) {
        if (!census.config().get("inactivity.cancelBookingsOnApproval", Boolean.class)) { return List.of(); }
        return bookings.inside(member, ym(from).atDay(1), to == null ? null : ym(to).atEndOfMonth(), true, false).stream()
                .map(b -> new CancelledBooking(CancelledBookingType.valueOf(b.type()), b.id(), b.sessionDate().toString())).toList();
    }
    @Transactional
    @Audited(action = AuditAction.INACTIVITY_RESOLVED, entityType = "'InactivityPeriod'", entity = "#id")
    public InactivityPeriod change(String id, Map<String, Object> patch, long version, boolean admin, boolean override) {
        census.require(Module.INACTIVITY); census.members.lock(); var old = get(id);
        if (old.version() != version) { throw new ApiException(ErrorCode.STALE_VERSION); }
        if (!LIVE.contains(old.state())) { throw new ApiException(ErrorCode.INACTIVITY_INVALID_STATE); }
        if (old.state() == InactivityState.ACTIVE && (patch.containsKey("fromMonth") || patch.containsKey("comments"))) { throw new ApiException(ErrorCode.READ_ONLY); }
        var e = new InactivityEdit(old); e.fromMonth = (String) patch.getOrDefault("fromMonth", old.fromMonth());
        e.toMonth = (String) patch.getOrDefault("toMonth", old.toMonth()); e.comments = (String) patch.getOrDefault("comments", old.comments());
        InactivityCalendar.range(ym(e.fromMonth), ym(e.toMonth));
        if (!override) { InactivityCalendar.change(ym(old.fromMonth()), ym(old.toMonth()), ym(e.fromMonth), ym(e.toMonth), earliest()); }
        if (ym(e.fromMonth).isAfter(earliest().plusMonths(census.config().get("inactivity.maxStartMonthsAhead", Integer.class)))) { throw new ApiException(ErrorCode.INACTIVITY_INVALID_RANGE); }
        overlap(e.memberId, id, e.fromMonth, e.toMonth);
        var history = new ArrayList<>(old.history()); history.add(new InactivityPeriod.HistoryEntry(clock.instant(), actor(), e.fromMonth, e.toMonth, admin ? ChangeSource.ADMIN : ChangeSource.MEMBER)); e.history = List.copyOf(history);
        if (override) { e.decision = new InactivityPeriod.Decision(clock.instant(), actor(), LifecycleDecision.APPROVED, e.decision == null ? null : e.decision.note(), true); }
        if (old.state() != InactivityState.REQUESTED) {
            var added = new ArrayList<>(old.cancelledBookings());
            if (ym(e.fromMonth).isBefore(ym(old.fromMonth()))) { added.addAll(cancelInside(e.memberId, e.fromMonth, ym(old.fromMonth()).minusMonths(1).toString())); }
            if (old.toMonth() != null && (e.toMonth == null || ym(e.toMonth).isAfter(ym(old.toMonth())))) { added.addAll(cancelInside(e.memberId, ym(old.toMonth()).plusMonths(1).toString(), e.toMonth)); }
            e.cancelledBookings = List.copyOf(added);
        }
        var result = save(e, old.version());
        emit("InactivityChanged", result, CensusValues.object("before", CensusValues.object("fromMonth", old.fromMonth(), "toMonth", old.toMonth()),
                "after", CensusValues.object("fromMonth", result.fromMonth(), "toMonth", result.toMonth())));
        return result;
    }
    @Transactional
    @Audited(action = AuditAction.INACTIVITY_RESOLVED, entityType = "'InactivityPeriod'", entity = "#id")
    public InactivityPeriod cancel(String id, boolean admin) {
        census.require(Module.INACTIVITY); census.members.lock(); var old = get(id);
        if (old.state() == InactivityState.CANCELLED) { return old; }
        if (admin ? old.state() != InactivityState.APPROVED || !ym(old.fromMonth()).isAfter(month())
                : old.state() != InactivityState.REQUESTED && old.state() != InactivityState.APPROVED) { throw new ApiException(ErrorCode.INACTIVITY_INVALID_STATE); }
        if (!admin && old.state() == InactivityState.APPROVED && ym(old.fromMonth()).isBefore(earliest())) { InactivityCalendar.deadline(earliest()); }
        var e = new InactivityEdit(old); cancelled(e, admin ? LifecycleCanceller.ADMIN : LifecycleCanceller.MEMBER, InactivityCancelReason.WITHDRAWN);
        var result = save(e, old.version()); emit("InactivityCancelled", result, Map.of("by", e.cancelledBy, "reason", e.cancelReason)); return result;
    }
    @Transactional
    @Audited(action = AuditAction.INACTIVITY_RESOLVED, entityType = "'InactivityPeriod'", entity = "#id")
    public InactivityPeriod terminate(String id, String to) {
        census.require(Module.INACTIVITY); census.members.lock(); var old = get(id);
        if (old.state() != InactivityState.ACTIVE) { throw new ApiException(ErrorCode.INACTIVITY_INVALID_STATE); }
        InactivityCalendar.range(ym(old.fromMonth()), ym(to)); var e = new InactivityEdit(old); e.toMonth = to;
        var h = new ArrayList<>(e.history); h.add(new InactivityPeriod.HistoryEntry(clock.instant(), actor(), e.fromMonth, to, ChangeSource.ADMIN)); e.history = List.copyOf(h);
        if (ym(to).isBefore(month())) { e.state = InactivityState.FINISHED; e.finishReason = InactivityFinishReason.ADMIN; e.finishedAt = clock.instant(); }
        var result = save(e, old.version()); emit(e.state == InactivityState.FINISHED ? "InactivityEnded" : "InactivityChanged", result, Map.of()); return result;
    }
    void cancelled(InactivityEdit e, LifecycleCanceller by, InactivityCancelReason reason) {
        e.state = InactivityState.CANCELLED; e.cancelledAt = clock.instant(); e.cancelledBy = by; e.cancelReason = reason;
    }
    InactivityPeriod save(InactivityEdit e, Long expected) { e.version = expected + 1; e.updatedAt = clock.instant(); return periods.save(e.snapshot(), expected); }
    static String actor() { var user = CurrentUser.current(); return user == null ? null : user.impersonation() == null ? user.accountId() : user.impersonation().actorAccountId(); }
    static Requester requester() { var u = CurrentUser.current(); return new Requester(actor(), u == null || u.impersonation() == null ? null : u.impersonation().memberId()); }
    void emit(String type, InactivityPeriod p, Map<String, Object> extra) {
        var payload = CensusValues.object("periodId", p.id(), "memberId", p.memberId(), "from", p.fromMonth(), "to", p.toMonth(), "origin", p.origin(),
                "fee", p.feeSnapshot(), "cancelledBookings", p.cancelledBookings(), "finishReason", p.finishReason(), "admin_text", p.decision() == null ? null : p.decision().note());
        payload.putAll(extra);
        if (!census.config().get("inactivity.cancelBookingsOnApproval", Boolean.class)) {
            payload.put("bookingsInside", bookings.inside(p.memberId(), ym(p.fromMonth()).atDay(1), p.toMonth() == null ? null : ym(p.toMonth()).atEndOfMonth(), false, false).size());
        }
        events.emit(type, "InactivityPeriod", p.id(), payload);
    }
}
