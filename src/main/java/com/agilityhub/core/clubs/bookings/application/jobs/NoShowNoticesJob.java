package com.agilityhub.core.clubs.bookings.application.jobs;

import com.agilityhub.core.clubs.bookings.application.NoShowNoticeClaims;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.platform.application.jobs.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-13 P3 `no-show-notices` (daily at `messaging.noShowNoticeTime`, club-local; no module guard; catch-up
 * unlimited because the scope is by state). Scope: {@link AttendanceRepository#dueForNoShowNotice} — `NO_SHOW` marks
 * not queued yet whose club-local class date is before the run's local date, also marks added late. The plan lists
 * one item per attendance (`WOULD_NOTIFY {attendanceId, bookingId, memberId, dogName, classDate}` in a dry run).
 *
 * <p>R-15-10's documented exception: the batch is <b>one</b> transaction. The first item still in scope claims the whole
 * set with {@link NoShowNoticeClaims#claim} inside the runner's item transaction (`noShowNotice.queuedAt` and the shared
 * `eventId` on every row, a single `NoShowNoticeDue{attendanceIds[], bookingIds[]}` on the outbox) and carries the
 * batch counters `{notices, late}`; the following items find their row already queued by that batch (the job lease
 * keeps any other run out) and only trace it. A mark changed to `PRESENT`/`NOTIFIED` before the claim is out of scope
 * and never notified. A second run, scheduled or manual, finds nothing (R-15-04). N-19 is S11's, one per booking
 * ({@link com.agilityhub.core.clubs.bookings.application.NoShowNotifications}).
 */
@Component
public class NoShowNoticesJob implements Job {
    static final String NOTIFY = "NOTIFY";
    private final AttendanceRepository attendances; private final NoShowNoticeClaims claims; private final BookingMemberAccess census;
    public NoShowNoticesJob(AttendanceRepository attendances, NoShowNoticeClaims claims, BookingMemberAccess census) {
        this.attendances = attendances; this.claims = claims; this.census = census;
    }
    @Override public JobName name() { return JobName.NO_SHOW_NOTICES; }

    @Override public List<JobItem> plan(JobContext context) {
        var due = attendances.dueForNoShowNotice(context.clubId(), context.localDate());
        var names = new HashMap<String, String>();
        census.dogs(due.stream().map(Attendance::dogId).filter(Objects::nonNull).distinct().toList()).forEach(dog -> names.put(dog.id(), dog.name()));
        return due.stream().map(a -> new JobItem("Attendance", a.id(), NOTIFY, detail(a, names.getOrDefault(a.dogId(), "")))).toList();
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        var attendance = attendances.findById(item.entityId()).orElse(null);
        if (attendance == null || attendance.state() != AttendanceState.NO_SHOW) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        if (queued(attendance)) {
            // Taken by the batch an earlier item of this run claimed (its counters already count it).
            return new JobEffect(NOTIFY, item.detail(), Map.of());
        }
        if (!attendance.classDate().isBefore(context.localDate())) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        var claim = claims.claim(context.localDate());
        if (!claim.attendanceIds().contains(attendance.id())) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        long late = attendances.byIds(claim.attendanceIds()).stream().filter(a -> late(a.classDate(), context.localDate())).count();
        return new JobEffect(NOTIFY, item.detail(), Map.of("notices", (long) claim.attendanceIds().size(), "late", late));
    }

    private static boolean queued(Attendance a) { return a.noShowNotice() != null && a.noShowNotice().queuedAt() != null; }
    /** R-15-13 `late`: the class is older than one day (a mark added two or more days after it). */
    public static boolean late(LocalDate classDate, LocalDate runDate) { return classDate.isBefore(runDate.minusDays(1)); }
    private static Map<String, Object> detail(Attendance a, String dogName) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("attendanceId", a.id()); detail.put("bookingId", a.bookingId()); detail.put("memberId", a.memberId());
        detail.put("dogName", dogName); detail.put("classDate", a.classDate().toString());
        return detail;
    }
}
