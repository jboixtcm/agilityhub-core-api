package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.bookings.application.BookingViews.map;

/**
 * S10 R-10-04 `PUT /class-sessions/{id}/attendance`: the bulk save of a sheet, one Mongo transaction serialised per class
 * by the S08 `seat_locks` row ({@link BookingTransactions}: same lanes, same retry on `WriteConflict` /
 * `TransientTransactionError`). (1) `$inc` the class's seat lock; (2) `version ≠ attendanceSummary.version` → 409
 * STALE_VERSION with the whole current sheet in `details.current`; (3) every item whose state differs is validated
 * ({@link AttendanceTransitions}), applied, appended to `history[]` and announced with one `AttendanceMarked`; an item
 * equal to the stored state is a no-op; «ha avisat» before `classEndsAt` cancels the booking through S08 in this same
 * transaction (R-10-05), so the seat is released and the waiting list told atomically with the mark; (4) the
 * `attendanceSummary` (S10-owned) is recomputed with `version + 1`. Any error rolls everything back. A save that changes
 * nothing keeps the version (resending the sheet is harmless).
 */
@Service
public class AttendanceSheetService {
    public record Item(String bookingId, AttendanceState state) { }
    private final BookingContext context; private final BookingTransactions transactions; private final SeatLockRepository locks;
    private final InstructorScheduleAccess schedule; private final BookingRepository bookings; private final AttendanceRepository attendances;
    private final AttendanceStatusCalculator calculator; private final BookingCancellationService cancellations; private final AttendanceSheetQuery sheets;
    private final EventPublisher publisher; private final AuditWriter audit;
    public AttendanceSheetService(BookingContext context, BookingTransactions transactions, SeatLockRepository locks, InstructorScheduleAccess schedule,
            BookingRepository bookings, AttendanceRepository attendances, AttendanceStatusCalculator calculator, BookingCancellationService cancellations,
            AttendanceSheetQuery sheets, EventPublisher publisher, AuditWriter audit) {
        this.context = context; this.transactions = transactions; this.locks = locks; this.schedule = schedule; this.bookings = bookings;
        this.attendances = attendances; this.calculator = calculator; this.cancellations = cancellations; this.sheets = sheets; this.publisher = publisher;
        this.audit = audit;
    }

    public Map<String, Object> save(String classSessionId, long version, List<Item> items, AttendanceCaller caller) {
        var ids = items.stream().map(Item::bookingId).toList();
        if (new HashSet<>(ids).size() != ids.size()) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "items")); }
        return transactions.write(List.of(classSessionId), () -> {
            locks.lock(classSessionId);
            var cls = schedule.find(classSessionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            AttendanceTransitions.requireMarkable(cls.state());
            if (version != cls.summary().version()) { throw new ApiException(ErrorCode.STALE_VERSION, Map.of("current", sheets.build(cls, caller))); }
            var now = context.now();
            var sheet = new AttendanceTransitions.Sheet(cls.state(), cls.endsAt(), calculator.window(cls.date()), caller.admin(),
                    context.flag("bookings.instructorLastMinuteNotice"));
            var found = new HashMap<String, Booking>(); bookings.byIds(ids).forEach(b -> found.put(b.id(), b));
            var marks = attendances.byBookings(ids);
            var applied = new ArrayList<String>();
            for (var item : items) {
                var booking = found.get(item.bookingId());
                if (booking == null || !booking.classSessionId().equals(classSessionId)) {
                    throw new ApiException(ErrorCode.ATTENDANCE_BOOKING_NOT_ACTIVE, Map.of("bookingId", item.bookingId()));
                }
                var stored = marks.get(booking.id());
                var from = stored == null ? AttendanceState.PENDING : stored.state();
                var decision = AttendanceTransitions.decide(new AttendanceTransitions.Item(booking.id(), from, item.state(), booking.state()), sheet, now);
                if (!decision.changes()) { continue; }
                apply(cls, booking, stored, from, item.state(), decision, caller, now);
                applied.add(booking.id());
            }
            if (!applied.isEmpty()) { summarise(cls, caller, now); }
            var result = sheets.build(schedule.find(classSessionId).orElseThrow(), caller);
            result.put("applied", applied);
            return result;
        });
    }

    private void apply(InstructorScheduleAccess.ClassView cls, Booking booking, Attendance stored, AttendanceState from, AttendanceState to,
            AttendanceTransitions.Decision decision, AttendanceCaller caller, Instant now) {
        Attendance.Notice notice = null;
        switch (decision.effect()) {
            case NOTICE_CANCEL -> {
                var cancellation = cancellations.cancelForNotice(booking.id(), caller.noticeActor());
                notice = new Attendance.Notice(now, cancellation.late(), cancellation.minutesBefore(), cancellation.seatReleased(),
                        cancellation.waitlistNotified(), false, cancellation.booking().state());
            }
            // R-10-05 / §13-4: after the class end the booking stays ACTIVE (it counts as done) and nobody is told.
            case NOTICE_RECORD -> notice = new Attendance.Notice(now, true, null, false, false, true, BookingState.ACTIVE);
            default -> { }
        }
        var history = new ArrayList<Attendance.Change>(stored == null || stored.history() == null ? List.of() : stored.history());
        history.add(new Attendance.Change(to, now, caller.accountId()));
        String id = stored == null ? UUID.randomUUID().toString() : stored.id();
        var next = new Attendance(id, TenantContext.require(), booking.id(), cls.id(), cls.date(), cls.startsAt(), cls.endsAt(), booking.dogId(),
                booking.memberId(), to, now, new Attendance.Marker(caller.accountId(), caller.role(), caller.displayName()), notice,
                stored == null ? null : stored.noShowNotice(), history, stored == null ? null : stored.version() + 1,
                stored == null ? now : stored.createdAt(), now);
        attendances.upsert(next, stored == null ? null : stored.version());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("bookingId", booking.id()); payload.put("classSessionId", cls.id()); payload.put("dogId", booking.dogId()); payload.put("memberId", booking.memberId());
        payload.put("state", to); payload.put("previousState", from); payload.put("by", map("accountId", caller.accountId(), "role", caller.role()));
        if (notice != null) { payload.put("late", notice.late()); payload.put("afterClassEnd", notice.afterClassEnd()); }
        publisher.publish(new AttendanceEvent(AttendanceEvent.Kind.AttendanceMarked, TenantContext.require(), id, now, payload, caller.accountId(), null,
                caller.admin() ? DomainEvent.Origin.BACKOFFICE : DomainEvent.Origin.INSTRUCTOR));
        if (decision.override()) {
            // S14 R-14-09: an ADMIN mark outside [T0, T1]; `changes` carry the class, the booking and the transition.
            audit.write(new AuditCommand(AuditAction.ATTENDANCE_OVERRIDDEN, "Attendance", id, booking.memberId(), map("state", from),
                    map("classSessionId", cls.id(), "bookingId", booking.id(), "state", to), null));
        }
    }

    /** R-10-04 step 4 over the R-10-02 rows (each booking once): `notifiedAfterEnd` keeps the S06 total exact (E6-T01 round 2). */
    private void summarise(InstructorScheduleAccess.ClassView cls, AttendanceCaller caller, Instant now) {
        int marked = 0, present = 0, notified = 0, noShow = 0, afterEnd = 0;
        for (var row : sheets.rows(cls.id())) {
            switch (row.state()) {
                case PRESENT -> { marked++; present++; }
                case NO_SHOW -> { marked++; noShow++; }
                case NOTIFIED -> { marked++; notified++; if (row.attendance().notice() != null && row.attendance().notice().afterClassEnd()) { afterEnd++; } }
                case PENDING -> { }
            }
        }
        schedule.attendanceSummary(cls.id(), new InstructorScheduleAccess.Summary(cls.summary().version() + 1, marked, present, notified, noShow, afterEnd,
                now, caller.displayName()));
    }
}
