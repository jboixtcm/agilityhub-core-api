package com.agilityhub.core.clubs.bookings.application.jobs;

import com.agilityhub.core.clubs.bookings.application.NoShowNoticeClaims;
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
 * not queued yet whose club-local class date is before the run's local date, also marks added late.
 *
 * <p>R-15-10's documented exception, one batch per club and day (E6-T04 round 2, review #4): the plan is <b>one</b>
 * `NoShowNoticeBatch` item keyed by the run's local date, with the attendances in its detail (`WOULD_NOTIFY {date,
 * attendances: [{attendanceId, bookingId, memberId, dogName, classDate}]}` in a dry run), or nothing when none is due. Its
 * application is the runner's single item transaction: {@link NoShowNoticeClaims#claim} writes `noShowNotice.queuedAt`
 * and the shared `eventId` on every row still in scope and puts a single `NoShowNoticeDue{attendanceIds[], bookingIds[]}`
 * on the outbox; the traced attendances and the counters `{notices, late}` come from that claim's result, never from the
 * plan. A mark changed to `PRESENT`/`NOTIFIED` before the claim is out of the batch and never notified, even if it goes back
 * to `NO_SHOW` before the run ends (no second claim within a run). A second run with nothing new claims nothing and emits
 * nothing (R-15-04). N-19 is S11's, one per booking (the S11 engine, with
 * {@link com.agilityhub.core.clubs.bookings.application.BookingNotificationFacts}).
 */
@Component
public class NoShowNoticesJob implements Job {
    static final String NOTIFY = "NOTIFY", ENTITY = "NoShowNoticeBatch";
    private final AttendanceRepository attendances; private final NoShowNoticeClaims claims; private final BookingMemberAccess census;
    public NoShowNoticesJob(AttendanceRepository attendances, NoShowNoticeClaims claims, BookingMemberAccess census) {
        this.attendances = attendances; this.claims = claims; this.census = census;
    }
    @Override public JobName name() { return JobName.NO_SHOW_NOTICES; }

    @Override public List<JobItem> plan(JobContext context) {
        var due = attendances.dueForNoShowNotice(context.clubId(), context.localDate());
        if (due.isEmpty()) { return List.of(); }
        return List.of(new JobItem(ENTITY, context.localDate().toString(), NOTIFY, detail(context.localDate(), due)));
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        var claim = claims.claim(context.localDate());
        if (claim.attendanceIds().isEmpty()) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        var byId = new HashMap<String, Attendance>();
        attendances.byIds(claim.attendanceIds()).forEach(a -> byId.put(a.id(), a));
        // The claim's own order (class date, then id), the same as the plan's.
        var claimed = claim.attendanceIds().stream().map(byId::get).filter(Objects::nonNull).toList();
        long late = claimed.stream().filter(a -> late(a.classDate(), context.localDate())).count();
        return new JobEffect(NOTIFY, detail(context.localDate(), claimed), Map.of("notices", (long) claim.attendanceIds().size(), "late", late));
    }

    /** R-15-13 `late`: the class is older than one day (a mark added two or more days after it). */
    public static boolean late(LocalDate classDate, LocalDate runDate) { return classDate.isBefore(runDate.minusDays(1)); }

    private Map<String, Object> detail(LocalDate runDate, List<Attendance> batch) {
        var names = new HashMap<String, String>();
        census.dogs(batch.stream().map(Attendance::dogId).filter(Objects::nonNull).distinct().toList()).forEach(dog -> names.put(dog.id(), dog.name()));
        var rows = new ArrayList<Map<String, Object>>();
        for (var a : batch) {
            var row = new LinkedHashMap<String, Object>();
            row.put("attendanceId", a.id()); row.put("bookingId", a.bookingId()); row.put("memberId", a.memberId());
            row.put("dogName", names.getOrDefault(a.dogId(), "")); row.put("classDate", a.classDate().toString());
            rows.add(row);
        }
        var detail = new LinkedHashMap<String, Object>();
        detail.put("date", runDate.toString()); detail.put("attendances", rows);
        return detail;
    }
}
