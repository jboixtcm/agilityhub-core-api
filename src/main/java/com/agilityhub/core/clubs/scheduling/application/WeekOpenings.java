package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.ClassState;
import com.agilityhub.core.clubs.scheduling.domain.WeekCalendarRules;
import com.agilityhub.core.clubs.scheduling.domain.WeekState;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSessionRepository;
import com.agilityhub.core.clubs.scheduling.persistence.Week;
import com.agilityhub.core.clubs.scheduling.persistence.WeekRepository;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S15 §3 marks of P1 on the S06 `Week`: `openedAt` (the week's booking opened) and `openingNotifiedAt` (N-33 sent once).
 * The week is identified by its ISO Monday; P1 gets or creates it (PENDING) so a week without planning still records its opening.
 */
@Service
public class WeekOpenings {
    public record Target(String weekId, LocalDate isoWeekStart, String state, int activeClasses, Instant openedAt, Instant openingNotifiedAt) { }
    private final WeekRepository weeks; private final ClassSessionRepository classes; private final Clock clock;
    public WeekOpenings(WeekRepository weeks, ClassSessionRepository classes, Clock clock) { this.weeks = weeks; this.classes = classes; this.clock = clock; }

    public Optional<Target> find(LocalDate isoWeekStart) { return weeks.forStart(isoWeekStart).map(this::target); }
    public Optional<Target> byId(String weekId) { return weeks.findById(weekId).map(this::target); }
    private Target target(Week week) {
        int active = (int) classes.forWeek(week.id()).stream().filter(c -> c.state() == ClassState.ACTIVE).count();
        return new Target(week.id(), week.startDate(), week.state().name(), active, week.openedAt(), week.openingNotifiedAt());
    }

    /** `targetWeek.openedAt = opensAt` (get-or-create) and, when N-33 goes out now, `openingNotifiedAt`; inside P1's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Target open(LocalDate isoWeekStart, Instant opensAt, boolean notified) {
        var now = clock.instant();
        var week = weeks.forStart(isoWeekStart).orElseGet(() -> weeks.getOrCreate(new Week(UUID.randomUUID().toString(), TenantContext.require(),
                WeekCalendarRules.isoYear(isoWeekStart), WeekCalendarRules.isoWeek(isoWeekStart), isoWeekStart, isoWeekStart.plusDays(6), WeekState.PENDING,
                null, null, null, null, null, null, 0L, now, null, now, null)));
        var saved = weeks.update(copy(week, opensAt, notified && week.openingNotifiedAt() == null ? now : week.openingNotifiedAt(), now), version(week));
        return target(saved);
    }
    /** The deferred N-33 (`WeekValidated` after the opening): set once, false when another delivery already did. */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean markNotified(String weekId) {
        var week = weeks.findById(weekId).orElse(null);
        if (week == null || week.openingNotifiedAt() != null) { return false; }
        var now = clock.instant();
        weeks.update(copy(week, week.openedAt(), now, now), version(week));
        return true;
    }
    private static long version(Week week) { return week.version() == null ? 0L : week.version(); }
    private static Week copy(Week w, Instant openedAt, Instant notifiedAt, Instant now) {
        return new Week(w.id(), w.clubId(), w.isoYear(), w.isoWeek(), w.startDate(), w.endDate(), w.state(), w.generatedAt(), w.generatedByAccountId(),
                w.weekdayTemplateId(), w.saturdayTemplateId(), w.validatedAt(), w.validatedByAccountId(), version(w) + 1, w.createdAt(), w.createdByAccountId(),
                now, w.updatedByAccountId(), openedAt, notifiedAt);
    }
}
