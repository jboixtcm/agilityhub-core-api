package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.DogFollowupPort;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.AttendanceCensusAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess.ClassView;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.bookings.application.BookingViews.map;

/**
 * S10 R-10-02/R-10-03 `GET /class-sessions/{id}/attendance` (21, D12), also the body of the `PUT` and the
 * `details.current` of its `409 STALE_VERSION`. Rows = the class's live bookings (ACTIVE, PAYMENT_PENDING) plus those
 * cancelled by «ha avisat» (`Attendance.state = NOTIFIED`, shown fixed), by `bookedAt` (§13-7); the member's, the club's
 * and the system's cancellations are out. Module-dependent keys are left out, not null: `waiting` and `waitlist`
 * without WAITLIST, `pendingTasksCount` without TASKS (or until E6-T03 serves {@link DogFollowupPort}).
 */
@Service
public class AttendanceSheetQuery {
    private final BookingContext context; private final AttendanceStatusCalculator calculator; private final InstructorScheduleAccess schedule;
    private final BookingRepository bookings; private final AttendanceRepository attendances; private final WaitlistEntryRepository waitlist;
    private final AttendanceCensusAccess census; private final PlanningCatalogAccess catalogs; private final DogFollowupPort followup;
    public AttendanceSheetQuery(BookingContext context, AttendanceStatusCalculator calculator, InstructorScheduleAccess schedule, BookingRepository bookings,
            AttendanceRepository attendances, WaitlistEntryRepository waitlist, AttendanceCensusAccess census, PlanningCatalogAccess catalogs, DogFollowupPort followup) {
        this.context = context; this.calculator = calculator; this.schedule = schedule; this.bookings = bookings; this.attendances = attendances;
        this.waitlist = waitlist; this.census = census; this.catalogs = catalogs; this.followup = followup;
    }

    /** A DRAFT class is not on any instructor screen (20, D12): 404, like another club's class. */
    public Map<String, Object> sheet(String classSessionId, AttendanceCaller caller) {
        var cls = schedule.find(classSessionId).filter(c -> !c.draft()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return build(cls, caller);
    }

    /** One sheet row: the booking with its attendance (null = PENDING). */
    record Row(Booking booking, Attendance attendance) {
        AttendanceState state() { return attendance == null ? AttendanceState.PENDING : attendance.state(); }
    }
    /** R-10-02 union, each booking once, by `bookedAt` (the repository order). */
    List<Row> rows(String classSessionId) {
        var marks = new HashMap<String, Attendance>(); attendances.findByClassSession(classSessionId).forEach(a -> marks.put(a.bookingId(), a));
        return bookings.forClass(classSessionId).stream().map(b -> new Row(b, marks.get(b.id())))
                .filter(r -> BookingRepository.LIVE.contains(r.booking().state()) || r.state() == AttendanceState.NOTIFIED).toList();
    }

    Map<String, Object> build(ClassView cls, AttendanceCaller caller) {
        var now = context.now(); var zone = context.zone(); var window = calculator.window(cls.date());
        boolean markable = "ACTIVE".equals(cls.state()) || "FINISHED".equals(cls.state());
        boolean waitlistOn = context.enabled(Module.WAITLIST);
        var classSession = map("id", cls.id(), "date", cls.date(), "startTime", cls.startTime(), "endTime", cls.endTime(), "displayDescription", cls.description(),
                "ring", cls.ring() == null ? null : map("id", cls.ring().id(), "name", cls.ring().name(), "color", cls.ring().color()),
                "instructorName", cls.instructorNames().isEmpty() ? null : cls.instructorNames(), "state", cls.state(), "capacity", cls.capacity(), "booked", cls.booked());
        if (waitlistOn) { classSession.put("waiting", cls.waiting()); }
        var summary = cls.summary();
        String noticeTime = context.config().get("messaging.noShowNoticeTime", String.class);
        var sheet = map("version", (long) summary.version(), "canMarkPresence", markable && window.canMarkPresence(caller.admin(), now),
                "canMarkNotice", markable && window.canMarkNotice(context.flag("bookings.instructorLastMinuteNotice"), now),
                "editableUntil", window.editableUntil(), "savedAt", summary.savedAt(), "savedByName", summary.savedByName(), "noShowNoticeTime", noticeTime);
        var rows = rows(cls.id());
        var pairs = census.pairs(rows.stream().map(r -> r.booking().dogId()).distinct().toList());
        var levels = levelCodes();
        boolean tasks = context.enabled(Module.TASKS) && followup.available();
        var pending = tasks ? followup.pendingTasks(pairs.keySet()) : Map.<String, Integer>of();
        var items = new ArrayList<Map<String, Object>>();
        for (var r : rows) {
            var b = r.booking(); var a = r.attendance(); var pair = pairs.get(b.dogId());
            var row = map("bookingId", b.id(), "dogId", b.dogId(), "dogName", pair == null ? null : pair.dogName(), "dogPhotoUrl", census.photoUrl(pair),
                    "memberId", b.memberId(), "memberFirstName", pair == null ? null : pair.memberFirstName(), "handlerName", pair == null ? null : pair.handlerName());
            if (pair != null && pair.handlerName() != null && !pair.handlerName().isBlank() && !pair.handlerName().equals(pair.memberFirstName())) {
                row.put("memberFullName", pair.memberFullName()); // R-10-00: «(abonat: {nom i cognom})» when someone else handles the dog
            }
            row.put("levelCode", pair == null ? null : levels.get(pair.levelId()));
            row.put("state", r.state()); row.put("final", r.state() == AttendanceState.NOTIFIED);
            row.put("markedAt", a == null ? null : a.markedAt()); row.put("markedByName", a == null || a.markedBy() == null ? null : a.markedBy().displayName());
            if (tasks) { row.put("pendingTasksCount", pending.getOrDefault(b.dogId(), 0)); }
            row.put("notice", r.state() == AttendanceState.NOTIFIED && a.notice() != null ? notice(a.notice(), zone) : null);
            row.put("noShowNotice", r.state() == AttendanceState.NO_SHOW ? noShowNotice(cls.date(), a.noShowNotice(), noticeTime, zone, now) : null);
            items.add(row);
        }
        var out = map("classSession", classSession, "sheet", sheet, "rows", items);
        if (waitlistOn) { out.put("waitlist", waitlist(cls.id(), levels)); }
        return out;
    }
    private Map<String, String> levelCodes() {
        if (!context.flag("levels.enabled")) { return Map.of(); } // R-10-16: no level chip anywhere
        var codes = new HashMap<String, String>(); catalogs.levelRefs().forEach((id, level) -> codes.put(id, level.code()));
        return codes;
    }
    private static Map<String, Object> notice(Attendance.Notice n, ZoneId zone) {
        return map("atLocal", n.at().atZone(zone).toLocalTime().withSecond(0).withNano(0).toString(), "at", n.at(), "late", n.late(), "minutesBefore", n.minutesBefore(),
                "seatReleased", n.seatReleased(), "waitlistNotified", n.waitlistNotified(), "afterClassEnd", n.afterClassEnd(), "bookingState", n.bookingState());
    }
    /**
     * R-10-06: the N-19 batch that claims (or claimed) the mark. Queued: the run of the day it was queued. Not yet: the
     * first `messaging.noShowNoticeTime` of a day after the class date that is still ahead (a late mark goes to the next
     * run). The local time is resolved as S06 R-06-14 does: a skipped time moves forward, a repeated one takes the first.
     */
    static Map<String, Object> noShowNotice(LocalDate classDate, Attendance.NoShowNotice stored, String noticeTime, ZoneId zone, Instant now) {
        var time = LocalTime.parse(noticeTime);
        Instant scheduled;
        if (stored != null && stored.queuedAt() != null) { scheduled = stored.queuedAt().atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant(); }
        else {
            var day = classDate.plusDays(1);
            while (day.atTime(time).atZone(zone).toInstant().isBefore(now)) { day = day.plusDays(1); }
            scheduled = day.atTime(time).atZone(zone).toInstant();
        }
        return map("scheduledFor", scheduled, "queuedAt", stored == null ? null : stored.queuedAt(), "sentAt", stored == null ? null : stored.sentAt());
    }
    private Map<String, Object> waitlist(String classSessionId, Map<String, String> levels) {
        var mode = context.waitlistMode();
        var entries = waitlist.live(classSessionId);
        var pairs = census.pairs(entries.stream().map(WaitlistEntry::dogId).distinct().toList());
        return map("mode", mode, "fifoConfirmMinutes", mode == WaitlistMode.FIFO ? context.integer("waitlist.fifoConfirmMinutes") : null,
                "entries", entries.stream().map(e -> {
                    var pair = pairs.get(e.dogId());
                    return map("entryId", e.id(), "dogName", pair == null ? null : pair.dogName(), "memberFirstName", pair == null ? null : pair.memberFirstName(),
                            "handlerName", pair == null ? null : pair.handlerName(), "levelCode", pair == null ? null : levels.get(pair.levelId()),
                            "joinedAt", e.joinedAt(), "state", e.state());
                }).toList());
    }
}
