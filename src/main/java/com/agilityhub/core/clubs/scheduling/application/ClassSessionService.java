package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ports.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class ClassSessionService {
    private final ClassSessionRepository classes; private final WeekRepository weeks; private final RingBlockRepository blocks;
    private final PlanningContext context; private final SchedulingTransactions transactions; private final SchedulingEvents events;
    private final SchedulingAudit audit; private final TrainingConflictPort training; private final Clock clock;
    public ClassSessionService(ClassSessionRepository classes, WeekRepository weeks, RingBlockRepository blocks, PlanningContext context,
            SchedulingTransactions transactions, SchedulingEvents events, SchedulingAudit audit, TrainingConflictPort training, Clock clock) {
        this.classes = classes; this.weeks = weeks; this.blocks = blocks; this.context = context; this.transactions = transactions;
        this.events = events; this.audit = audit; this.training = training; this.clock = clock;
    }
    public ClassSession require(String id) { return classes.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    /** @param manualCapacity whether `capacity` was set by hand (MANUAL) rather than derived from the levels (AUTO) */
    public record Slot(String id, LocalDate date, String startTime, List<String> levelIds, int capacity, String state, boolean manualCapacity) { }
    /** The DRAFT or ACTIVE class of a ring at a club-local date and start time. */
    public Optional<Slot> slot(LocalDate date, String startTime, String ringId) {
        return classes.findLive(date, startTime, ringId).map(c -> new Slot(c.id(), c.date(), c.startTime(), c.levelIds(), c.capacity(), c.state().name(),
                c.capacityMode() == com.agilityhub.core.clubs.scheduling.domain.CapacityMode.MANUAL));
    }
    @PreAuthorize("hasRole('ADMIN')")
    public ClassSession create(LocalDate date, String start, String end, String ring, List<String> levels, List<String> instructors,
            Integer capacity, String description, boolean cancelBookings) {
        return transactions.write(() -> {
            var now = clock.instant(); var actor = events.actor(); var monday = WeekCalendarRules.monday(date);
            var week = weeks.forStart(monday).orElseGet(() -> weeks.getOrCreate(new Week(UUID.randomUUID().toString(), TenantContext.require(),
                    WeekCalendarRules.isoYear(monday), WeekCalendarRules.isoWeek(monday), monday, monday.plusDays(6), WeekState.PENDING,
                    null, null, null, null, null, null, 0L, now, actor, now, actor)));
            var draft = new ClassSession(UUID.randomUUID().toString(), TenantContext.require(), week.id(), date, start, end, null, null, ring, levels, instructors,
                    capacity == null ? 0 : capacity, capacity == null ? CapacityMode.AUTO : CapacityMode.MANUAL, description,
                    week.state() == WeekState.VALIDATED ? ClassState.ACTIVE : ClassState.DRAFT, new ClassSession.Counters(0, 0),
                    new ClassSession.Risk(false, List.of(), null, null), null, null, null, null, -1L, now, actor, now, actor);
            var edit = new SessionEdit(draft); validate(edit, cancelBookings, true);
            var saved = classes.insert(edit.snapshot(now, actor));
            events.publish(SchedulingEvent.Kind.ClassSessionCreated, saved.id(), Map.of("classId", saved.id())); return saved;
        });
    }
    @PreAuthorize("hasRole('ADMIN')")
    @SuppressWarnings("unchecked")
    public ClassSession patch(String id, long version, Map<String, Object> patch, boolean cancelBookings) {
        return transactions.write(() -> {
            var before = require(id); if (before.version() != version) { throw new ApiException(ErrorCode.STALE_VERSION); }
            ClassSessionRules.editable(before.state(), patch.keySet()); var edit = new SessionEdit(before);
            if (patch.containsKey("ringId")) { edit.ringId = (String) patch.get("ringId"); }
            if (patch.containsKey("levelIds")) { edit.levels = (List<String>) patch.get("levelIds"); }
            if (patch.containsKey("instructorIds")) { edit.instructors = (List<String>) patch.get("instructorIds"); }
            if (patch.containsKey("capacity")) { var cap = (Integer) patch.get("capacity"); edit.capacityMode = cap == null ? CapacityMode.AUTO : CapacityMode.MANUAL; edit.capacity = cap == null ? 0 : cap; }
            if (patch.containsKey("startTime")) { edit.startTime = (String) patch.get("startTime"); }
            if (patch.containsKey("endTime")) { edit.endTime = (String) patch.get("endTime"); }
            if (patch.containsKey("description")) { edit.description = (String) patch.get("description"); }
            if (patch.containsKey("notes")) { edit.notes = (String) patch.get("notes"); }
            if (patch.containsKey("riskExempt")) { requireActive(before); edit.risk = risk(before, (Boolean) patch.get("riskExempt")); }
            if (!Set.of("notes").containsAll(patch.keySet())) {
                validate(edit, cancelBookings, !Objects.equals(before.ringId(), edit.ringId) || !before.startTime().equals(edit.startTime) || !before.endTime().equals(edit.endTime));
            }
            var after = classes.update(edit.snapshot(clock.instant(), events.actor()), version);
            var diff = new LinkedHashMap<String, Object>();
            for (String key : patch.keySet()) { Object old = field(before, key), next = field(after, key); if (!Objects.equals(old, next)) { var change = new LinkedHashMap<String, Object>(); change.put("before", old); change.put("after", next); diff.put(key, change); } }
            events.publish(SchedulingEvent.Kind.ClassSessionUpdated, id, Map.of("classId", id, "diff", diff, "bookedCount", before.counters().booked()));
            if (before.counters().booked() > 0) { audit.updated(before, after); }
            if (before.risk().exempt() != after.risk().exempt()) { riskEvent(before, after); }
            return after;
        });
    }
    private Object field(ClassSession c, String key) {
        return switch (key) {
            case "ringId" -> c.ringId(); case "levelIds" -> c.levelIds(); case "instructorIds" -> c.instructorIds();
            case "capacity" -> c.capacityMode() == CapacityMode.AUTO ? null : c.capacity(); case "startTime" -> c.startTime();
            case "endTime" -> c.endTime(); case "description" -> c.description(); case "notes" -> c.notes(); case "riskExempt" -> c.risk().exempt();
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR);
        };
    }
    @PreAuthorize("hasRole('ADMIN')")
    public ClassSession exemption(String id, boolean exempt) {
        return transactions.write(() -> {
            var before = require(id); requireActive(before); if (before.risk().exempt() == exempt) { return before; }
            var edit = new SessionEdit(before); edit.risk = risk(before, exempt);
            var after = classes.update(edit.snapshot(clock.instant(), events.actor()), before.version()); riskEvent(before, after); return after;
        });
    }
    private void riskEvent(ClassSession before, ClassSession after) {
        events.publish(SchedulingEvent.Kind.ClassRiskExemptionChanged, after.id(), Map.of("classId", after.id(), "exempt", after.risk().exempt())); audit.exempted(before, after);
    }
    private void requireActive(ClassSession c) { if (c.state() != ClassState.ACTIVE) { throw new ApiException(ErrorCode.INVALID_STATE); } }
    private ClassSession.Risk risk(ClassSession c, boolean exempt) { return new ClassSession.Risk(exempt, c.risk().notifiedBookingIds(), c.risk().adminNotifiedAt(), c.risk().lowAlertSentAt()); }
    private void validate(SessionEdit edit, boolean cancelBookings, boolean moving) {
        var config = context.config(); var catalog = context.catalog();
        var start = WeekTemplateRules.time(edit.startTime); var end = WeekTemplateRules.time(edit.endTime);
        ClassSessionRules.times(edit.before.date(), start, end, config.get("classes.slotMinutes", Integer.class), context.opening(config));
        ClassSessionRules.references(edit.instructors, edit.ringId, edit.levels, edit.description, config.get("classes.maxInstructorsPerClass", Integer.class), config.get("levels.enabled", Boolean.class), catalog);
        edit.capacity = context.capacity(edit.levels, edit.capacityMode, edit.capacity, catalog, config);
        if (edit.capacity < edit.counters.booked()) { throw new ApiException(ErrorCode.CAPACITY_BELOW_BOOKINGS); }
        var zone = ZoneId.of(config.club().timeZone());
        edit.startsAt = WeekCalendarRules.resolve(edit.before.date(), start, zone).instant(); edit.endsAt = WeekCalendarRules.resolve(edit.before.date(), end, zone).instant();
        if (moving && edit.ringId != null) {
            if (blocks.between(edit.startsAt, edit.endsAt).stream().anyMatch(b -> b.ringId().equals(edit.ringId))) { throw new ApiException(ErrorCode.RING_BLOCKED); }
            var booked = config.modules().contains(Module.FREE_TRAINING) ? training.findActiveBookings(edit.ringId, edit.startsAt, edit.endsAt) : List.<TrainingConflictPort.Booking>of();
            if (!booked.isEmpty()) {
                if (!cancelBookings) { throw new ApiException(ErrorCode.RING_HAS_BOOKINGS, Map.of("bookings", booked)); }
                training.cancelByClub(booked.stream().map(TrainingConflictPort.Booking::id).toList(), "CLASS_SESSION");
            }
        }
    }
    public int finishEnded(Instant now) {
        return transactions.write(() -> {
            var cutoff = now.minusSeconds(context.config().get("classes.finishGraceMinutes", Integer.class) * 60L); int count = 0;
            for (var before : classes.findAll()) {
                if (before.state() != ClassState.ACTIVE || before.endsAt().isAfter(cutoff)) { continue; }
                var edit = new SessionEdit(before); edit.state = ClassState.FINISHED;
                classes.update(edit.snapshot(now, events.actor()), before.version()); classes.finishedAt(before.id(), now); count++;
            }
            return count;
        });
    }
}
