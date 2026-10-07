package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriodRepository;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/** Census side of P5 b–e: planning only reads; apply joins the runner's per-item transaction. */
@Service
public class CensusExpirations {
    private final CensusAccess census;
    private final InactivityPeriodRepository periods;
    private final InactivityScheduler inactivity;
    private final LeaveScheduler leaves;
    private final LifecycleBookings bookings;
    private final CensusRepository<DogDocument> documents;
    private final CensusEvents events;
    private final JobActionMarks marks;
    private final Clock clock;
    public CensusExpirations(CensusAccess census, InactivityPeriodRepository periods, InactivityScheduler inactivity,
            LeaveScheduler leaves, LifecycleBookings bookings, CensusRepository<DogDocument> documents,
            CensusEvents events, JobActionMarks marks, Clock clock) {
        this.census = census; this.periods = periods; this.inactivity = inactivity; this.leaves = leaves;
        this.bookings = bookings; this.documents = documents; this.events = events; this.marks = marks; this.clock = clock;
    }
    public List<JobItem> inactivity(JobContext context) {
        var items = new ArrayList<JobItem>(); var month = YearMonth.from(context.localDate());
        for (var p : periods.inStates("APPROVED", "ACTIVE", "REQUESTED")) {
            boolean due = !YearMonth.parse(p.fromMonth()).isAfter(month);
            switch (p.state()) {
                case APPROVED -> { if (due) {
                    items.add(new JobItem("InactivityPeriod", p.id(), "START_INACTIVITY"));
                    if (p.toMonth() != null && YearMonth.parse(p.toMonth()).isBefore(month)) {
                        items.add(new JobItem("InactivityPeriod", p.id(), "END_INACTIVITY"));
                    }
                } }
                case ACTIVE -> { if (p.toMonth() != null && YearMonth.parse(p.toMonth()).isBefore(month)) {
                    items.add(new JobItem("InactivityPeriod", p.id(), "END_INACTIVITY"));
                } }
                case REQUESTED -> { if (YearMonth.parse(p.fromMonth()).isBefore(month)) {
                    items.add(new JobItem("InactivityPeriod", p.id(), "CANCEL_STALE_INACTIVITY"));
                } }
                default -> { }
            }
        }
        return List.copyOf(items);
    }
    public List<JobItem> leaves(JobContext context) {
        return census.members.matching(Criteria.where("status").is("ACTIVE")).stream()
                .filter(m -> m.leaveDate != null && m.leaveDate.isBefore(context.localDate()))
                .map(m -> new JobItem("Member", m.id, "LEAVE", Map.of("memberId", m.id,
                        "futureBookings", bookings.inside(m.id, m.leaveDate.plusDays(1), null, false, true).size()))).toList();
    }
    public List<JobItem> signups(JobContext context) {
        int days = context.parameter("signup.pendingExpiryDays", Integer.class);
        var members = census.members.matching(Criteria.where("status").is("PENDING")).stream()
                .filter(m -> submitted(m) != null && age(m, context) > days).toList();
        if (members.isEmpty()) { return List.of(); }
        return List.of(new JobItem("Member", context.clubId(), "REMIND_SIGNUPS", Map.of("count", members.size(),
                "oldestDays", members.stream().mapToLong(m -> age(m, context)).max().orElseThrow(),
                "memberIds", members.stream().map(m -> m.id).sorted().toList())));
    }
    private Instant submitted(Member member) {
        Object value = member.signup == null ? null : member.signup.get("submittedAt");
        if (value instanceof Instant at) { return at; }
        if (value instanceof Date date) { return date.toInstant(); }
        return value instanceof String text ? Instant.parse(text) : null;
    }
    private long age(Member member, JobContext context) {
        return ChronoUnit.DAYS.between(submitted(member).atZone(context.zone()).toLocalDate(), context.localDate());
    }
    public List<JobItem> documents(JobContext context) {
        int days = context.parameter("messaging.documentReminderDays", Integer.class);
        if (days <= 0) { return List.of(); }
        return documents.matching(Criteria.where("state").is("PENDING")).stream().filter(d -> documentDue(d, context, days))
                .map(d -> new JobItem("DogDocument", d.id, "REMIND_DOCUMENT")).toList();
    }
    private boolean documentDue(DogDocument doc, JobContext context, int days) {
        var dog = census.dogs.findById(doc.dogId).orElse(null);
        if (dog == null || !"ACTIVE".equals(dog.status)) { return false; }
        var member = census.members.findById(dog.memberId).orElse(null);
        var at = doc.lastReminderAt == null ? doc.createdAt : doc.lastReminderAt;
        return member != null && "ACTIVE".equals(member.status) && at != null && "PENDING".equals(doc.state)
                && !at.atZone(context.zone()).toLocalDate().plusDays(days).isAfter(context.localDate());
    }
    public JobEffect apply(JobContext context, JobItem item) {
        return switch (item.action()) {
            case "START_INACTIVITY" -> effect(item, inactivity.transition(item.entityId(), context.localDate(), "APPROVED"), "startedInactivity");
            case "END_INACTIVITY" -> effect(item, inactivity.transition(item.entityId(), context.localDate(), "ACTIVE"), "endedInactivity");
            case "CANCEL_STALE_INACTIVITY" -> effect(item, inactivity.transition(item.entityId(), context.localDate(), "REQUESTED"), "cancelledInactivity");
            case "LEAVE" -> effect(item, leaves.execute(item.entityId(), context.localDate()), "leftMembers");
            case "REMIND_SIGNUPS" -> remindSignups(context);
            case "REMIND_DOCUMENT" -> remindDocument(context, item);
            default -> throw new IllegalArgumentException("Unknown census expiration action");
        };
    }
    private JobEffect remindSignups(JobContext context) {
        String date = context.localDate().toString();
        if (marks.contains("REMIND_SIGNUPS", date)) { return JobEffect.of("ALREADY_DONE", "alreadyDone"); }
        var current = signups(context);
        if (current.isEmpty()) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        var item = current.getFirst(); marks.record("REMIND_SIGNUPS", date);
        events.emit("SignupPendingAging", "Club", TenantContext.require(), item.detail());
        return effect(item, true, "signupReminders");
    }
    private JobEffect remindDocument(JobContext context, JobItem item) {
        census.members.lock(); var doc = documents.require(item.entityId());
        int days = context.parameter("messaging.documentReminderDays", Integer.class);
        if (days <= 0 || !documentDue(doc, context, days)) { return effect(item, false, "documentReminders"); }
        doc.lastReminderAt = clock.instant(); documents.save(doc);
        events.emit("DocumentReminderDue", "DogDocument", doc.id, Map.of("dogId", doc.dogId, "type", doc.type));
        return effect(item, true, "documentReminders");
    }
    private static JobEffect effect(JobItem item, boolean changed, String counter) {
        return changed ? new JobEffect(item.action(), item.detail(), Map.of(counter, 1L)) : new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of());
    }
}
