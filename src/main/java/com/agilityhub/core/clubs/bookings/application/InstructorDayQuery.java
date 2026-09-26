package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess.ClassView;
import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.platform.application.Module;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.bookings.application.BookingViews.map;

/**
 * S10 R-10-01 `GET /instructor/day` (20). Global vision: the dropdown lists the active instructors by `shortName`, with
 * no «Tot el club» entry, and preselects the caller's profile (`instructorId=me` too); an ADMIN without one gets the
 * first by `shortName` (assumption in force). Seven day chips from today (assumption) tell whether the selected
 * instructor has classes; the day shows that instructor's classes (ACTIVE, FINISHED, CANCELLED; never DRAFT) and every
 * ring block of the day, whoever created it. `waiting` only with WAITLIST. An empty day has no classes (the literal is
 * the front's).
 */
@Service
public class InstructorDayQuery {
    static final int DAY_CHIPS = 7;
    private final BookingContext context; private final PlanningCatalogAccess catalogs; private final InstructorScheduleAccess schedule;
    private final RingScheduleAccess rings; private final AttendanceStatusCalculator calculator;
    public InstructorDayQuery(BookingContext context, PlanningCatalogAccess catalogs, InstructorScheduleAccess schedule, RingScheduleAccess rings,
            AttendanceStatusCalculator calculator) {
        this.context = context; this.catalogs = catalogs; this.schedule = schedule; this.rings = rings; this.calculator = calculator;
    }

    public Map<String, Object> day(LocalDate date, String instructorId, AttendanceCaller caller) {
        var zone = context.zone(); var today = context.today(); var day = date == null ? today : date;
        var active = activeInstructors(catalogs);
        String selected = "me".equals(instructorId) ? caller.instructorId() : instructorId != null ? instructorId
                : caller.instructorId() != null ? caller.instructorId() : active.stream().findFirst().map(PlanningCatalogAccess.InstructorRef::id).orElse(null);
        var chipsFrom = today.atStartOfDay(zone).toInstant(); var chipsTo = today.plusDays(DAY_CHIPS).atStartOfDay(zone).toInstant();
        var from = day.atStartOfDay(zone).toInstant(); var to = day.plusDays(1).atStartOfDay(zone).toInstant();
        var chipClasses = mine(schedule.between(chipsFrom, chipsTo), selected);
        var days = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < DAY_CHIPS; i++) {
            var d = today.plusDays(i);
            days.add(map("date", d, "hasClasses", chipClasses.stream().anyMatch(c -> c.date().equals(d))));
        }
        boolean waitlist = context.enabled(Module.WAITLIST);
        var classes = mine(schedule.between(from, to), selected).stream().map(c -> {
            var s = c.summary();
            var row = map("id", c.id(), "startTime", c.startTime(), "endTime", c.endTime(), "displayDescription", c.description(),
                    "ring", c.ring() == null ? null : map("id", c.ring().id(), "name", c.ring().name(), "color", c.ring().color()), "state", c.state(),
                    "booked", c.booked(), "capacity", c.capacity());
            if (waitlist) { row.put("waiting", c.waiting()); }
            row.put("individual", c.capacity() == 1);
            row.put("attendance", map("status", calculator.status(c.date(), c.state(), s.marked(), c.booked(), s.notified(), s.notifiedAfterEnd()),
                    "marked", s.marked(), "total", AttendanceStatusCalculator.total(c.booked(), s.notified(), s.notifiedAfterEnd())));
            return row;
        }).toList();
        var ringNames = new HashMap<String, String>(); catalogs.rings().forEach(r -> ringNames.put(r.id(), r.name()));
        boolean activities = context.enabled(Module.ACTIVITIES);
        var blocks = rings.blocks(from, to, true).stream().filter(b -> activities || !"ACTIVITY".equals(b.reason())).map(b -> map("id", b.id(),
                "ringName", ringNames.get(b.ringId()), "fromLocal", b.from().isBefore(from) ? "00:00" : hhmm(b.from(), zone),
                "toLocal", hhmm(b.to(), zone), "kind", b.kind(), "reason", b.reason(), "note", b.note(),
                "createdByName", b.createdByName())).toList();
        return map("date", day, "timeZone", zone.getId(), "selectedInstructorId", selected,
                "instructors", active.stream().map(i -> map("id", i.id(), "shortName", i.shortName())).toList(), "days", days, "classes", classes, "ringBlocks", blocks);
    }
    /** Active instructors by `shortName` (R-10-01 dropdown and the ADMIN default). */
    static List<PlanningCatalogAccess.InstructorRef> activeInstructors(PlanningCatalogAccess catalogs) {
        return catalogs.instructorRefs().stream().filter(PlanningCatalogAccess.InstructorRef::active)
                .sorted(Comparator.comparing((PlanningCatalogAccess.InstructorRef i) -> i.shortName(), java.text.Collator.getInstance(Locale.ROOT))
                        .thenComparing(PlanningCatalogAccess.InstructorRef::id)).toList();
    }
    private static List<ClassView> mine(List<ClassView> classes, String instructorId) {
        return instructorId == null ? List.of() : classes.stream().filter(c -> c.instructorIds().contains(instructorId)).toList();
    }
    static String hhmm(Instant instant, ZoneId zone) { return instant.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString(); }
}
