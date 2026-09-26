package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.domain.ClassState;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSession;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSessionRepository;
import com.agilityhub.core.shared.application.LocaleContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S10 reads classes through this boundary (never the persistence type) for the instructor day (20), the week agenda
 * (D12, the classes of `CalendarQuery` form B: ACTIVE, FINISHED and CANCELLED, never DRAFT) and the attendance sheet
 * (21), and writes the one field it owns, `ClassSession.attendanceSummary` (S10 §7 contract with S06).
 */
@Service
public class InstructorScheduleAccess {
    public record Summary(int version, int marked, int present, int notified, int noShow, int notifiedAfterEnd, Instant savedAt, String savedByName) {
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, null, null);
    }
    public record Ring(String id, String name, String color) { }
    /** @param instructorNames «Marc, Neus» in `instructorIds` order (R-10-16); @param description the display description in the request locale */
    public record ClassView(String id, String state, LocalDate date, String startTime, String endTime, Instant startsAt, Instant endsAt, Ring ring,
            List<String> instructorIds, String instructorNames, String description, List<String> levelIds, int capacity, int booked, int waiting, Summary summary) {
        public boolean draft() { return "DRAFT".equals(state); }
    }
    private final ClassSessionRepository classes; private final PlanningContext context; private final SessionProjection projection;
    private final PlanningCatalogAccess catalogs;
    public InstructorScheduleAccess(ClassSessionRepository classes, PlanningContext context, SessionProjection projection, PlanningCatalogAccess catalogs) {
        this.classes = classes; this.context = context; this.projection = projection; this.catalogs = catalogs;
    }
    /** Any state; another club's class is empty. */
    public Optional<ClassView> find(String id) { return id == null ? Optional.empty() : classes.findById(id).map(c -> views(List.of(c)).getFirst()); }
    /** Classes starting in `[from, to)` except DRAFT, by start (form B's filter). */
    public List<ClassView> between(Instant from, Instant to) {
        var found = classes.startingBetween(from, to).stream().filter(c -> c.state() != ClassState.DRAFT).toList();
        return found.isEmpty() ? List.of() : views(found);
    }
    private List<ClassView> views(List<ClassSession> found) {
        var rings = new HashMap<String, Ring>(); catalogs.rings().forEach(r -> rings.put(r.id(), new Ring(r.id(), r.name(), r.color())));
        var names = new HashMap<String, String>(); context.catalog().instructors().forEach(i -> names.put(i.id(), i.name()));
        var locale = LocaleContext.current();
        return found.stream().map(c -> {
            var s = c.attendanceSummary();
            var ids = c.instructorIds() == null ? List.<String>of() : c.instructorIds();
            return new ClassView(c.id(), c.state().name(), c.date(), c.startTime(), c.endTime(), c.startsAt(), c.endsAt(), c.ringId() == null ? null : rings.get(c.ringId()),
                    ids, String.join(", ", ids.stream().map(names::get).filter(Objects::nonNull).toList()), projection.description(c, locale),
                    c.levelIds() == null ? List.of() : c.levelIds(), c.capacity(), c.counters() == null ? 0 : c.counters().booked(),
                    c.counters() == null ? 0 : c.counters().waiting(),
                    new Summary(s.version(), s.marked(), s.present(), s.notified(), s.noShow(), s.notifiedAfterEnd(), s.savedAt(), s.savedByName()));
        }).toList();
    }
    /**
     * R-10-04 step 4, inside the attendance save transaction (which already holds the class's `seat_locks` row): the
     * S10-owned summary, and the class `version` bumped so an S06 edit read before this save fails its compare-and-set
     * instead of writing the old summary back ({@link SessionEdit} copies it).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attendanceSummary(String id, Summary summary) {
        classes.attendanceSummary(id, new ClassSession.AttendanceSummary(summary.version(), summary.marked(), summary.present(), summary.notified(),
                summary.noShow(), summary.notifiedAfterEnd(), summary.savedAt(), summary.savedByName()));
    }
}
