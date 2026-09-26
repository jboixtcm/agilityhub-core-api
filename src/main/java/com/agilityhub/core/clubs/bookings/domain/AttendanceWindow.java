package com.agilityhub.core.clubs.bookings.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * S10 R-10-03 marking window in club-local time: `T0` = 00:00 of `classDate`, `T1` = 23:59:59 of
 * `classDate + attendance.editDays` (S06 R-06-14 DST semantics: `atStartOfDay` resolves a skipped midnight forward).
 */
public record AttendanceWindow(Instant opensAt, Instant editableUntil) {
    /** `attendance.status` of 20/D12 and of the S06 calendar (S10 §6 after the `/instructor/day` example). */
    public enum Status { NONE, PENDING, DONE, CLOSED }

    public static AttendanceWindow of(LocalDate classDate, ZoneId zone, int editDays) {
        if (editDays < 0) { throw new IllegalArgumentException("attendance.editDays must not be negative"); }
        var closes = classDate.plusDays(editDays + 1L).atStartOfDay(zone).toInstant();
        return new AttendanceWindow(classDate.atStartOfDay(zone).toInstant(), closes.minusSeconds(1));
    }
    public boolean notOpen(Instant now) { return now.isBefore(opensAt); }
    public boolean closed(Instant now) { return now.isAfter(editableUntil); }
    /** `PRESENT`, `NO_SHOW` and back to `PENDING`: an INSTRUCTOR inside `[T0, T1]`, an ADMIN always (R-10-03). */
    public boolean canMarkPresence(boolean admin, Instant now) { return admin || !notOpen(now) && !closed(now); }
    /** An ADMIN mark outside `[T0, T1]` is audited as `ATTENDANCE_OVERRIDDEN` (S14 R-14-09). */
    public boolean overrides(boolean admin, Instant now) { return admin && (notOpen(now) || closed(now)); }
    /** `NOTIFIED`: `bookings.instructorLastMinuteNotice` and `now ≤ T1`, for every role (R-10-03); the booking must still be ACTIVE. */
    public boolean canMarkNotice(boolean instructorLastMinuteNotice, Instant now) { return instructorLastMinuteNotice && !closed(now); }

    /**
     * NONE before `T0` and for a DRAFT class; CLOSED after `T1` and for a CANCELLED class (nothing can be marked);
     * inside the window PENDING while `marked < total`, DONE otherwise (also a class without rows).
     */
    public Status status(Instant now, String classState, int marked, int total) {
        if ("DRAFT".equals(classState)) { return Status.NONE; }
        if ("CANCELLED".equals(classState) || closed(now)) { return Status.CLOSED; }
        if (notOpen(now)) { return Status.NONE; }
        return marked < total ? Status.PENDING : Status.DONE;
    }
}
