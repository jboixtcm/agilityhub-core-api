package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.Map;

/**
 * S10 §5 attendance state machine and the R-10-03 permissions of one sheet item, evaluated at the save instant.
 * An item equal to the current state is a no-op (resending the whole sheet is harmless, R-10-04); `NOTIFIED` is final;
 * the booking must be live (ACTIVE or PAYMENT_PENDING) and, for `NOTIFIED`, ACTIVE; a DRAFT or CANCELLED class
 * accepts nothing (409 INVALID_STATE, CATALEG_ERRORS §1). Every other refusal is a 422 (rule 0).
 */
public final class AttendanceTransitions {
    private AttendanceTransitions() { }
    /** NONE = no-op; MARK = PRESENT/NO_SHOW/PENDING; NOTICE_CANCEL = S08 cancellation (`now < classEndsAt`); NOTICE_RECORD = afterClassEnd. */
    public enum Effect { NONE, MARK, NOTICE_CANCEL, NOTICE_RECORD }
    public record Item(String bookingId, AttendanceState from, AttendanceState to, BookingState bookingState) { }
    public record Sheet(String classState, Instant classEndsAt, AttendanceWindow window, boolean admin, boolean instructorLastMinuteNotice) { }
    /** @param override an ADMIN mark outside `[T0, T1]` (audited `ATTENDANCE_OVERRIDDEN`) */
    public record Decision(Effect effect, boolean override) {
        public boolean changes() { return effect != Effect.NONE; }
    }

    /** R-10-03: only an ACTIVE or FINISHED class is marked (FINISHED until `T1`). */
    public static void requireMarkable(String classState) {
        if (!"ACTIVE".equals(classState) && !"FINISHED".equals(classState)) { throw new ApiException(ErrorCode.INVALID_STATE); }
    }

    public static Decision decide(Item item, Sheet sheet, Instant now) {
        requireMarkable(sheet.classState());
        if (item.from() == item.to()) { return new Decision(Effect.NONE, false); }
        if (item.from() == AttendanceState.NOTIFIED) { throw new ApiException(ErrorCode.ATTENDANCE_NOTIFIED_FINAL); }
        boolean live = item.bookingState() == BookingState.ACTIVE || item.bookingState() == BookingState.PAYMENT_PENDING;
        if (!live) { throw notActive(item.bookingId()); }
        var window = sheet.window();
        if (item.to() == AttendanceState.NOTIFIED) {
            if (!sheet.instructorLastMinuteNotice()) { throw new ApiException(ErrorCode.INSTRUCTOR_NOTICE_DISABLED); }
            if (item.bookingState() != BookingState.ACTIVE) { throw notActive(item.bookingId()); }
            if (window.closed(now)) { throw closed(window); }
            return new Decision(now.isBefore(sheet.classEndsAt()) ? Effect.NOTICE_CANCEL : Effect.NOTICE_RECORD, false);
        }
        if (!window.canMarkPresence(sheet.admin(), now)) {
            if (window.notOpen(now)) { throw new ApiException(ErrorCode.ATTENDANCE_NOT_OPEN); }
            throw closed(window);
        }
        return new Decision(Effect.MARK, window.overrides(sheet.admin(), now));
    }
    private static ApiException notActive(String bookingId) { return new ApiException(ErrorCode.ATTENDANCE_BOOKING_NOT_ACTIVE, Map.of("bookingId", bookingId)); }
    private static ApiException closed(AttendanceWindow window) {
        return new ApiException(ErrorCode.ATTENDANCE_WINDOW_CLOSED, Map.of("editableUntil", window.editableUntil()));
    }
}
