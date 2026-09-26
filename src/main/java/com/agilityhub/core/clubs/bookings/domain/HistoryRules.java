package com.agilityhub.core.clubs.bookings.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * S10 R-10-14 class rows of the member history (25): which bookings appear and their `state`, `counts` and
 * `detail.kind`; the front picks the sentence. Live bookings are on 03, so a booking appears once its class has
 * started (ACTIVE) or as soon as it is cancelled, future ones included (§13-14).
 *
 * <table>
 * <tr><th>Booking</th><th>state</th><th>counts</th><th>detail.kind</th></tr>
 * <tr><td>ACTIVE started, PRESENT or unmarked</td><td>DONE</td><td>true</td><td>—</td></tr>
 * <tr><td>ACTIVE started, NO_SHOW</td><td>NO_SHOW</td><td>true</td><td>NO_SHOW</td></tr>
 * <tr><td>ACTIVE, «ha avisat» after the class end (R-10-05)</td><td>CANCELLED_LATE</td><td>true</td><td>INSTRUCTOR_NOTICE (assumption)</td></tr>
 * <tr><td>CANCELLED_LATE, `INSTRUCTOR_NOTICE`</td><td>CANCELLED_LATE</td><td>true</td><td>INSTRUCTOR_NOTICE</td></tr>
 * <tr><td>CANCELLED_LATE by the member</td><td>CANCELLED_LATE</td><td>true</td><td>BY_MEMBER</td></tr>
 * <tr><td>CANCELLED, `INSTRUCTOR_NOTICE`</td><td>CANCELLED</td><td>false</td><td>INSTRUCTOR_NOTICE_IN_TIME</td></tr>
 * <tr><td>CANCELLED by the member (`MEMBER`, `SWAP`)</td><td>CANCELLED</td><td>false</td><td>BY_MEMBER_IN_TIME</td></tr>
 * <tr><td>cancelled by an impersonating admin (BACKOFFICE), in time or late</td><td>CANCELLED / CANCELLED_LATE</td><td>per late</td><td>BY_CLUB_ON_BEHALF</td></tr>
 * <tr><td>CANCELLED `INACTIVITY`, `LEAVE`, `PAYMENT_TIMEOUT` (system)</td><td>CANCELLED</td><td>false</td><td>SYSTEM</td></tr>
 * <tr><td>CANCELLED_BY_CLUB (`CLUB_CLASS_CANCELLED`, `AUTO_CANCELLED`)</td><td>CANCELLED_BY_CLUB</td><td>false</td><td>BY_CLUB (+ the club's text)</td></tr>
 * </table>
 */
public final class HistoryRules {
    private HistoryRules() { }
    public enum State { DONE, NO_SHOW, CANCELLED, CANCELLED_LATE, CANCELLED_BY_CLUB }
    public enum DetailKind { BY_MEMBER, BY_MEMBER_IN_TIME, INSTRUCTOR_NOTICE, INSTRUCTOR_NOTICE_IN_TIME, BY_CLUB_ON_BEHALF, SYSTEM, BY_CLUB, NO_SHOW }
    /**
     * @param impersonated the cancellation was made by an admin impersonating the member (`cancelledBy.impersonatedMemberId`)
     * @param noticeAt the save instant of an «ha avisat» after the class end (`Attendance.notice.at`)
     */
    public record ClassBooking(BookingState state, BookingCancelReason reason, boolean impersonated, Instant classStartsAt, Instant cancelledAt,
            AttendanceState attendance, boolean afterClassEnd, Instant noticeAt) { }
    /** @param at the instant the sentence names («el {dd/mm} a les {hh:mm}»): the cancellation or the late notice; null otherwise */
    public record Entry(State state, boolean counts, DetailKind kind, Instant at) { }

    public static Optional<Entry> classEntry(ClassBooking b, Instant now) {
        return Optional.ofNullable(switch (b.state()) {
            case PAYMENT_PENDING -> null;
            case ACTIVE -> b.classStartsAt().isAfter(now) ? null
                    : b.attendance() == AttendanceState.NO_SHOW ? new Entry(State.NO_SHOW, true, DetailKind.NO_SHOW, null)
                    : b.attendance() == AttendanceState.NOTIFIED && b.afterClassEnd() ? new Entry(State.CANCELLED_LATE, true, DetailKind.INSTRUCTOR_NOTICE, b.noticeAt())
                    : new Entry(State.DONE, true, null, null);
            case CANCELLED_BY_CLUB -> new Entry(State.CANCELLED_BY_CLUB, false, DetailKind.BY_CLUB, null);
            case CANCELLED_LATE -> new Entry(State.CANCELLED_LATE, true, b.impersonated() ? DetailKind.BY_CLUB_ON_BEHALF
                    : b.reason() == BookingCancelReason.INSTRUCTOR_NOTICE ? DetailKind.INSTRUCTOR_NOTICE : DetailKind.BY_MEMBER, b.cancelledAt());
            case CANCELLED -> new Entry(State.CANCELLED, false, inTime(b), null);
        });
    }
    private static DetailKind inTime(ClassBooking b) {
        if (b.impersonated()) { return DetailKind.BY_CLUB_ON_BEHALF; }
        if (b.reason() == null) { return DetailKind.BY_MEMBER_IN_TIME; }
        return switch (b.reason()) {
            case INSTRUCTOR_NOTICE -> DetailKind.INSTRUCTOR_NOTICE_IN_TIME;
            case INACTIVITY, LEAVE, PAYMENT_TIMEOUT -> DetailKind.SYSTEM;
            case CLUB_CLASS_CANCELLED, AUTO_CANCELLED -> DetailKind.BY_CLUB;
            case MEMBER, SWAP -> DetailKind.BY_MEMBER_IN_TIME;
        };
    }
}
