package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.persistence.ClassSession;
import com.agilityhub.core.clubs.scheduling.domain.*;
import java.time.*;
import java.util.*;

/** Transaction-local mutable copy; only the immutable snapshot reaches persistence. */
final class SessionEdit {
    final ClassSession before;
    String ringId, startTime, endTime, description, notes;
    List<String> levels, instructors;
    int capacity; CapacityMode capacityMode; ClassState state;
    ClassSession.Counters counters; ClassSession.Risk risk; ClassSession.Cancellation cancellation;
    Instant startsAt, endsAt;
    SessionEdit(ClassSession c) {
        before = c; ringId = c.ringId(); startTime = c.startTime(); endTime = c.endTime(); description = c.description(); notes = c.notes();
        levels = c.levelIds(); instructors = c.instructorIds(); capacity = c.capacity(); capacityMode = c.capacityMode(); state = c.state();
        counters = c.counters(); risk = c.risk(); cancellation = c.cancellation(); startsAt = c.startsAt(); endsAt = c.endsAt();
    }
    ClassSession snapshot(Instant now, String actor) {
        return new ClassSession(before.id(), before.clubId(), before.weekId(), before.date(), startTime, endTime, startsAt, endsAt,
                ringId, levels, instructors, capacity, capacityMode, description, state, counters, risk, cancellation, before.origin(),
                before.placementId(), notes, before.version() + 1, before.createdAt(), before.createdByAccountId(), now, actor, before.finishedAt(),
                before.attendanceSummary());
    }
}
