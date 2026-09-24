package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.ClassState;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * S08 reads and writes class sessions through this boundary (never the persistence type). Counter writes join the
 * booking transaction, which already serialises the class through `seat_locks`: lock order `seat_locks` →
 * `class_sessions`. No catalog reference lock is taken here, so bookings of different classes never conflict.
 */
@Service
public class ClassSessionBookingAccess {
    public record Session(String id, String state, LocalDate date, String startTime, String endTime, Instant startsAt, Instant endsAt,
            String ringId, List<String> levelIds, List<String> instructorIds, int capacity, int booked, int waiting,
            boolean riskExempt, Instant lowAlertSentAt, long version) {
        public boolean active() { return "ACTIVE".equals(state); }
    }
    public record Labels(String description, String ringName, String ringColor, List<String> levelNames, String instructorNames) { }
    /** What happens to `risk.lowAlertSentAt` with the new counters (S15 R-15-12b guard). */
    public enum LowAlert { KEEP, SET, CLEAR }
    private final ClassSessionRepository classes; private final PlanningContext context; private final SessionProjection projection;
    private final com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess catalogs; private final SchedulingEvents events; private final Clock clock;
    public ClassSessionBookingAccess(ClassSessionRepository classes, PlanningContext context, SessionProjection projection,
            com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess catalogs, SchedulingEvents events, Clock clock) {
        this.classes = classes; this.context = context; this.projection = projection; this.catalogs = catalogs; this.events = events; this.clock = clock;
    }
    public Optional<Session> find(String id) { return id == null ? Optional.empty() : classes.findById(id).map(ClassSessionBookingAccess::view); }
    public Session require(String id) { return find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    /** ACTIVE classes starting in `[from, to)`, by start (S15 P1 warm-up of the bookable-classes base cache). */
    public List<Session> activeBetween(Instant from, Instant to) {
        return classes.findActiveBetween(com.agilityhub.core.shared.application.TenantContext.require(), from, to).stream()
                .sorted(Comparator.comparing(ClassSession::startsAt).thenComparing(ClassSession::id)).map(ClassSessionBookingAccess::view).toList();
    }
    public Labels labels(Session s, Locale locale) {
        var c = classes.findById(s.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); var catalog = context.catalog();
        var ring = catalogs.rings().stream().filter(r -> r.id().equals(c.ringId())).findFirst();
        var levels = catalog.levels().stream().filter(l -> c.levelIds().contains(l.id())).sorted(Comparator.comparingInt(l -> l.order()))
                .map(l -> l.name().resolve(locale).value()).toList();
        var instructors = String.join(", ", catalog.instructors().stream().filter(i -> c.instructorIds().contains(i.id())).map(i -> i.name()).toList());
        return new Labels(projection.description(c, locale), ring.map(r -> r.name()).orElse(null), ring.map(r -> r.color()).orElse(null), levels, instructors);
    }
    /**
     * Writes the booked/waiting counters inside the caller's transaction; the class must be ACTIVE. Only the S08 booking
     * writers call it (ArchUnit `COUNTER_WRITERS`, E5-T09). A raise of `booked` above the capacity is refused with
     * `CLASS_FULL`; a lower count is always written, so a class left over capacity by older data can still release seats.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Session counters(String id, int booked, int waiting, LowAlert lowAlert) {
        var before = classes.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (before.state() != ClassState.ACTIVE) { throw new ApiException(ErrorCode.INVALID_STATE); }
        if (booked < 0 || waiting < 0) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        int current = before.counters() == null ? 0 : before.counters().booked();
        if (booked > before.capacity() && booked > current) { throw new ApiException(ErrorCode.CLASS_FULL); }
        var edit = new SessionEdit(before); var now = clock.instant(); edit.counters = new ClassSession.Counters(booked, waiting);
        var risk = before.risk() == null ? new ClassSession.Risk(false, List.of(), null, null) : before.risk();
        if (lowAlert != LowAlert.KEEP) {
            edit.risk = new ClassSession.Risk(risk.exempt(), risk.notifiedBookingIds(), risk.adminNotifiedAt(), lowAlert == LowAlert.SET ? now : null);
        }
        return view(classes.update(edit.snapshot(now, events.actor()), before.version()));
    }
    private static Session view(ClassSession c) {
        return new Session(c.id(), c.state().name(), c.date(), c.startTime(), c.endTime(), c.startsAt(), c.endsAt(), c.ringId(),
                c.levelIds() == null ? List.of() : c.levelIds(), c.instructorIds() == null ? List.of() : c.instructorIds(), c.capacity(),
                c.counters() == null ? 0 : c.counters().booked(), c.counters() == null ? 0 : c.counters().waiting(),
                c.risk() != null && c.risk().exempt(), c.risk() == null ? null : c.risk().lowAlertSentAt(), c.version() == null ? 0 : c.version());
    }
}
