package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess.ClassView;
import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingOccupancyPort;
import com.agilityhub.core.platform.application.Module;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.bookings.application.BookingViews.map;
import static com.agilityhub.core.clubs.bookings.application.InstructorDayQuery.hhmm;

/**
 * S10 R-10-15 `GET /instructor/week` (D12) and the source of its PDF. The ISO week of `date` in the club's time zone,
 * Monday to Saturday (Sunday only when it has items, assumption in force); `relative` against today's week. Classes are
 * those of the S06 calendar form B (ACTIVE, FINISHED and CANCELLED, never DRAFT) with `attendanceStatus` and, with
 * WAITLIST, `waiting`; trainings come from S09's occupancy (R-09-12, `training.slotMinutes` long: half height) and only
 * with FREE_TRAINING; ring blocks are S06's with the reason, the note and who created them. `instructorId` narrows the
 * classes only (`me` = the caller's profile; a shared class of `classes.maxInstructorsPerClass > 1` matches any of its
 * instructors, whose names are joined «Marc, Neus»); `ringId` narrows everything. `rows[]` = the distinct start times.
 */
@Service
public class WeekAgendaQuery {
    private final BookingContext context; private final PlanningCatalogAccess catalogs; private final InstructorScheduleAccess schedule;
    private final RingScheduleAccess rings; private final TrainingOccupancyPort occupancy; private final AttendanceStatusCalculator calculator;
    public WeekAgendaQuery(BookingContext context, PlanningCatalogAccess catalogs, InstructorScheduleAccess schedule, RingScheduleAccess rings,
            TrainingOccupancyPort occupancy, AttendanceStatusCalculator calculator) {
        this.context = context; this.catalogs = catalogs; this.schedule = schedule; this.rings = rings; this.occupancy = occupancy; this.calculator = calculator;
    }

    public Map<String, Object> week(LocalDate date, String instructorId, String ringId, AttendanceCaller caller) {
        var zone = context.zone(); var today = context.today();
        var monday = (date == null ? today : date).with(DayOfWeek.MONDAY); var sunday = monday.plusDays(6);
        var from = monday.atStartOfDay(zone).toInstant(); var to = monday.plusDays(7).atStartOfDay(zone).toInstant();
        var currentMonday = today.with(DayOfWeek.MONDAY);
        String relative = monday.equals(currentMonday) ? "CURRENT" : monday.isBefore(currentMonday) ? "PAST" : "FUTURE";
        String instructor = "me".equals(instructorId) ? caller.instructorId() : instructorId;
        Predicate<ClassView> classFilter = c -> (instructorId == null || instructor != null && c.instructorIds().contains(instructor))
                && (ringId == null || c.ring() != null && ringId.equals(c.ring().id()));
        var ringViews = catalogs.rings();
        var ringNames = new HashMap<String, String>(); ringViews.forEach(r -> ringNames.put(r.id(), r.name()));
        boolean waitlist = context.enabled(Module.WAITLIST);
        var cells = new ArrayList<Map<String, Object>>();
        for (var c : schedule.between(from, to)) {
            if (!classFilter.test(c)) { continue; }
            var s = c.summary();
            var cell = map("date", c.date(), "time", c.startTime(), "endTime", c.endTime(), "kind", "CLASS", "ringName", c.ring() == null ? null : c.ring().name(),
                    "classId", c.id(), "displayDescription", c.description(), "ringColor", c.ring() == null ? null : c.ring().color(),
                    "instructorName", c.instructorNames().isEmpty() ? null : c.instructorNames(), "booked", c.booked(), "capacity", c.capacity());
            if (waitlist) { cell.put("waiting", c.waiting()); }
            cell.put("state", c.state());
            cell.put("attendanceStatus", calculator.status(c.date(), c.state(), s.marked(), c.booked(), s.notified(), s.notifiedAfterEnd()));
            cells.add(cell);
        }
        var ringFilter = ringId == null ? null : List.of(ringId);
        if (context.enabled(Module.FREE_TRAINING)) {
            for (var t : occupancy.occupancy(from, to, ringFilter, "INSTRUCTOR")) {
                if (t.type() != TrainingOccupancyPort.Type.TRAINING) { continue; }
                var start = t.from().atZone(zone);
                cells.add(map("date", start.toLocalDate(), "time", hhmm(t.from(), zone), "endTime", hhmm(t.to(), zone), "kind", "TRAINING",
                        "ringName", ringNames.get(t.ringId()), "who", Objects.toString(t.memberName(), "") + " + " + Objects.toString(t.dogName(), ""),
                        "trainingBookingId", t.id()));
            }
        }
        boolean activities = context.enabled(Module.ACTIVITIES);
        for (var b : rings.blocks(from, to, true)) {
            if (ringId != null && !ringId.equals(b.ringId()) || !activities && "ACTIVITY".equals(b.reason())) { continue; }
            var start = b.from().isBefore(from) ? from : b.from();
            cells.add(map("date", start.atZone(zone).toLocalDate(), "time", hhmm(start, zone), "endTime", hhmm(b.to(), zone), "kind", "BLOCK",
                    "ringName", ringNames.get(b.ringId()), "blockId", b.id(), "reason", b.reason(), "note", b.note(), "createdByName", b.createdByName()));
        }
        cells.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDate) m.get("date")).thenComparing(m -> (String) m.get("time"))
                .thenComparing(m -> (String) m.get("kind")).thenComparing(m -> Objects.toString(m.getOrDefault("classId",
                        m.getOrDefault("trainingBookingId", m.get("blockId"))), "")));
        boolean sundayItems = cells.stream().anyMatch(m -> sunday.equals(m.get("date")));
        var rows = new TreeSet<String>(); cells.forEach(m -> rows.add((String) m.get("time")));
        var filters = map("instructorId", instructor == null && instructorId != null ? instructorId : instructor, "ringId", ringId,
                "instructors", InstructorDayQuery.activeInstructors(catalogs).stream().map(i -> map("id", i.id(), "shortName", i.shortName())).toList(),
                "rings", ringViews.stream().filter(PlanningCatalogAccess.RingView::active).map(r -> map("id", r.id(), "name", r.name(), "color", r.color())).toList());
        return map("week", map("startDate", monday, "endDate", sundayItems ? sunday : monday.plusDays(5), "relative", relative), "filters", filters,
                "rows", List.copyOf(rows), "cells", cells);
    }
}
