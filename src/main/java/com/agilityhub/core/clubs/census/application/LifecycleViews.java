package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriod;
import com.agilityhub.core.clubs.census.persistence.leave.LeaveRequest;
import com.agilityhub.core.clubs.census.inactivity.domain.InactivityCalendar;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import java.time.*;
import java.util.*;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** Explicit S13 response allowlists; persistence documents are never serialized as API responses. */
@Service
public class LifecycleViews {
    private final CensusAccess census; private final InactivityPeriodService inactivity; private final LeaveRequestService leaves;
    private final InactivityFeeService fees; private final LifecycleBookings bookings; private final ClubClock local;
    public LifecycleViews(CensusAccess census, InactivityPeriodService inactivity, LeaveRequestService leaves, InactivityFeeService fees, LifecycleBookings bookings, ClubClock local) {
        this.census = census; this.inactivity = inactivity; this.leaves = leaves; this.fees = fees; this.bookings = bookings; this.local = local;
    }
    public Map<String, Object> member(String id) {
        var m = census.members.require(id);
        return object("id", id, "fullName", java.util.stream.Stream.of(m.firstName, m.lastName1, m.lastName2).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(" ")), "memberNumber", m.memberNumber);
    }
    public Map<String, Object> editable(InactivityPeriod p) {
        boolean pending = p.state() == InactivityState.REQUESTED, approved = p.state() == InactivityState.APPROVED;
        boolean start = !YearMonth.parse(p.fromMonth()).isBefore(inactivity.earliest());
        return object("fromMonth", pending || approved && start, "toMonth", pending || approved || p.state() == InactivityState.ACTIVE, "cancel", pending || approved && start);
    }
    public Map<String, Object> period(InactivityPeriod p) {
        return object("id", p.id(), "member", member(p.memberId()), "fromMonth", p.fromMonth(), "toMonth", p.toMonth(), "comments", p.comments(), "state", p.state(),
                "origin", p.origin(), "requestedAt", p.requestedAt(), "requestedBy", p.requestedBy(), "decision", p.decision(), "feeSnapshot", p.feeSnapshot(),
                "startedAt", p.startedAt(), "finishedAt", p.finishedAt(), "finishReason", p.finishReason(), "cancelledAt", p.cancelledAt(), "cancelledBy", p.cancelledBy(),
                "cancelReason", p.cancelReason(), "cancelledBookings", p.cancelledBookings(), "bookingsInside",
                census.config().get("inactivity.cancelBookingsOnApproval", Boolean.class) ? null : bookings.inside(p.memberId(), YearMonth.parse(p.fromMonth()).atDay(1),
                        p.toMonth() == null ? null : YearMonth.parse(p.toMonth()).atEndOfMonth(), false, false).size(), "history", p.history(), "editable", editable(p), "version", p.version());
    }
    public Map<String, Object> request(LeaveRequest r) {
        var member = census.members.require(r.memberId()); var m = member(r.memberId());
        m.putAll(object("status", member.status, "leaveDate", member.leaveDate, "leftAt", member.leftAt, "leftReason", member.leftReason));
        return object("id", r.id(), "member", m, "source", r.source(), "origin", r.origin(), "requestedAt", r.requestedAt(), "requestedBy", r.requestedBy(),
                "requestedDate", r.requestedDate(), "reasonKey", r.reasonKey(), "reason", label(r.reasonKey()), "nps", r.nps(), "comment", r.comment(), "state", r.state(),
                "decision", r.decision(), "executedAt", r.executedAt(), "cancelledAt", r.cancelledAt(), "cancelledBy", r.cancelledBy(), "cancelReason", r.cancelReason(),
                "cancelledBookings", r.cancelledBookings(), "packBalanceId", r.packBalanceId(), "version", r.version());
    }
    public List<Map<String, Object>> reasons() {
        return rows(census.config().get("leave.reasons", List.class)).stream().filter(r -> "MEMBER".equals(r.get("audience")))
                .map(r -> object("key", r.get("key"), "label", label(string(r.get("key"))))).toList();
    }
    public String label(String key) {
        if (key == null) { return null; }
        return rows(census.config().get("leave.reasons", List.class)).stream().filter(r -> key.equals(r.get("key"))).findFirst().map(r -> {
            var text = map(r.get("label")); if (text.containsKey("values")) { text = map(text.get("values")); }
            return Objects.toString(text.getOrDefault(LocaleContextHolder.getLocale().getLanguage(), text.get(census.config().club().defaultLocale())), key);
        }).orElse(key);
    }
    public Map<String, Object> inactivityContext(String memberId) {
        int deadline = census.config().get("inactivity.requestDeadlineDay", Integer.class);
        return object("earliestFromMonth", inactivity.earliest().toString(), "proposedFromMonth", inactivity.earliest().toString(), "deadlineDay", deadline, "fee", fees.snapshot(),
                "periods", inactivity.ofMember(memberId).stream().map(p -> object("id", p.id(), "fromMonth", p.fromMonth(), "toMonth", p.toMonth(), "state", p.state(), "comments", p.comments(),
                        "fee", p.feeSnapshot() == null ? fees.snapshot() : p.feeSnapshot(), "editable", editable(p), "version", p.version())).toList());
    }
    public Map<String, Object> plannedLeave(String memberId) {
        var m = census.members.require(memberId);
        if (!"ACTIVE".equals(m.status) || m.leaveDate == null) { return null; }
        var r = m.leaveRequestId == null ? null : leaves.get(m.leaveRequestId);
        return object("date", m.leaveDate, "source", r == null ? LeaveSource.MIGRATED : r.source(), "requestId", m.leaveRequestId,
                "since", r == null || r.decision() == null ? m.updatedAt : r.decision().at(), "cancellable", true);
    }
    public Map<String, Object> overviewInactivity(String memberId) {
        var today = YearMonth.from(local.today(TenantContext.require())); var out = new LinkedHashMap<String, Object>();
        for (var p : inactivity.ofMember(memberId)) {
            String key = p.state() == InactivityState.REQUESTED ? "pendingRequest" : p.state() == InactivityState.ACTIVE || p.state() == InactivityState.APPROVED
                    ? (YearMonth.parse(p.fromMonth()).isAfter(today) ? "upcoming" : "current") : null;
            if (key != null) { out.put(key, object("id", p.id(), "fromMonth", p.fromMonth(), "toMonth", p.toMonth(), "state", p.state(), "fee", p.feeSnapshot())); }
        }
        return out;
    }
    public Map<String, Object> leaveContext(String memberId) {
        var m = census.members.require(memberId);
        return object("offerInactivity", census.enabled(Module.INACTIVITY) && "MONTHLY".equals(census.references.plan(m.planId).get("type")), "fee", fees.snapshot(),
                "defaultDate", local.today(TenantContext.require()), "fullMonthIfLater", census.config().get("leave.fullMonthIfLater", Boolean.class),
                "npsEnabled", census.config().get("leave.npsEnabled", Boolean.class), "reasons", reasons(), "plannedLeave", plannedLeave(memberId),
                "requests", leaves.ofMember(memberId).stream().map(r -> object("id", r.id(), "requestedDate", r.requestedDate(), "reasonKey", r.reasonKey(), "nps", r.nps(),
                        "comment", r.comment(), "state", r.state(), "decision", r.decision())).toList());
    }
    public Map<String, Object> preview(String memberId, String from, String to) {
        var start = YearMonth.parse(from); var end = to == null ? null : YearMonth.parse(to); InactivityCalendar.range(start, end);
        var inside = bookings.inside(memberId, start.atDay(1), end == null ? null : end.atEndOfMonth(), false, false);
        var counts = object("classes", inside.stream().filter(b -> b.type().equals("CLASS")).count(), "total", inside.size());
        for (var entry : Map.of(Module.WAITLIST, "WAITLIST", Module.FREE_TRAINING, "TRAINING", Module.ACTIVITIES, "ACTIVITY").entrySet()) {
            if (census.enabled(entry.getKey())) { counts.put(switch (entry.getKey()) { case WAITLIST -> "waitlist"; case FREE_TRAINING -> "trainings"; default -> "activities"; }, inside.stream().filter(b -> b.type().equals(entry.getValue())).count()); }
        }
        var schedule = new ArrayList<Map<String, Object>>(); var fee = fees.snapshot();
        if (fee != null) {
            YearMonth until = end == null ? start.plusMonths(1) : end;
            for (var month = start; !month.isAfter(until); month = month.plusMonths(1)) { schedule.add(object("month", month.toString(), "amount", month.equals(start) ? fee.firstMonth() : fee.followingMonths())); }
        }
        return object("bookingsInside", counts, "feeSchedule", schedule, "earliestMonthViolation", start.isBefore(inactivity.earliest()));
    }
}
