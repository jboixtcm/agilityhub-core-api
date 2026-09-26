package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static com.agilityhub.core.clubs.bookings.domain.AttendanceState.*;
import static com.agilityhub.core.clubs.bookings.domain.AttendanceTransitions.Effect;
import static org.assertj.core.api.Assertions.*;

/** S10 R-10-03 permissions and the §5 state machine, evaluated at the save instant (no Spring, no Mongo). */
class AttendanceTransitionsTest {
    static final LocalDate MONDAY = LocalDate.of(2026, 8, 3);
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid"), BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");

    static AttendanceTransitions.Sheet sheet(ZoneId zone, boolean admin, String classState, boolean notice) {
        // Class Mon 03-08 08:30–09:30 local, attendance.editDays = 1.
        var ends = MONDAY.atTime(9, 30).atZone(zone).toInstant();
        return new AttendanceTransitions.Sheet(classState, ends, AttendanceWindow.of(MONDAY, zone, 1), admin, notice);
    }
    static AttendanceTransitions.Decision decide(AttendanceState from, AttendanceState to, BookingState booking, AttendanceTransitions.Sheet sheet, Instant now) {
        return AttendanceTransitions.decide(new AttendanceTransitions.Item("b1", from, to, booking), sheet, now);
    }
    static ErrorCode refused(AttendanceState from, AttendanceState to, BookingState booking, AttendanceTransitions.Sheet sheet, Instant now) {
        var thrown = catchThrowableOfType(ApiException.class, () -> decide(from, to, booking, sheet, now));
        assertThat(thrown).as(from + " → " + to + " at " + now).isNotNull();
        return thrown.code();
    }
    static Instant local(String dateTime, ZoneId zone) { return LocalDateTime.parse(dateTime).atZone(zone).toInstant(); }

    @Test void T_10_01_instructorWindowInMadridOpensAtLocalMidnightAndClosesAtTheEndOfTheNextDay() {
        var instructor = sheet(MADRID, false, "ACTIVE", true);
        assertThat(refused(PENDING, PRESENT, BookingState.ACTIVE, instructor, local("2026-08-02T23:59", MADRID))).isEqualTo(ErrorCode.ATTENDANCE_NOT_OPEN);
        assertThat(decide(PENDING, PRESENT, BookingState.ACTIVE, instructor, local("2026-08-03T00:00", MADRID)).effect()).isEqualTo(Effect.MARK);
        assertThat(decide(PENDING, NO_SHOW, BookingState.ACTIVE, instructor, local("2026-08-04T23:59:59", MADRID)).effect()).isEqualTo(Effect.MARK);
        var closed = catchThrowableOfType(ApiException.class, () -> decide(PENDING, PRESENT, BookingState.ACTIVE, instructor, local("2026-08-05T00:00", MADRID)));
        assertThat(closed.code()).isEqualTo(ErrorCode.ATTENDANCE_WINDOW_CLOSED);
        assertThat(closed.details()).isEqualTo(Map.of("editableUntil", Instant.parse("2026-08-04T21:59:59Z")));
        // ADMIN: always, and outside the window the mark is an override (audited ATTENDANCE_OVERRIDDEN).
        var admin = sheet(MADRID, true, "ACTIVE", true);
        assertThat(decide(PENDING, PRESENT, BookingState.ACTIVE, admin, local("2026-08-02T23:59", MADRID))).isEqualTo(new AttendanceTransitions.Decision(Effect.MARK, true));
        assertThat(decide(PRESENT, NO_SHOW, BookingState.ACTIVE, admin, local("2026-08-05T09:00", MADRID))).isEqualTo(new AttendanceTransitions.Decision(Effect.MARK, true));
        assertThat(decide(PENDING, PRESENT, BookingState.ACTIVE, admin, local("2026-08-03T08:25", MADRID))).isEqualTo(new AttendanceTransitions.Decision(Effect.MARK, false));
    }

    @Test void T_10_01_theSameInstantsInBuenosAiresHitDifferentLimits() {
        var madrid = sheet(MADRID, false, "ACTIVE", true); var buenosAires = sheet(BUENOS_AIRES, false, "ACTIVE", true);
        // 2026-08-02T22:00Z is Mon 00:00 in Madrid but Sun 19:00 in Buenos Aires (UTC−3).
        var mondayMadrid = Instant.parse("2026-08-02T22:00:00Z");
        assertThat(decide(PENDING, PRESENT, BookingState.ACTIVE, madrid, mondayMadrid).effect()).isEqualTo(Effect.MARK);
        assertThat(refused(PENDING, PRESENT, BookingState.ACTIVE, buenosAires, mondayMadrid)).isEqualTo(ErrorCode.ATTENDANCE_NOT_OPEN);
        // 2026-08-04T23:30Z: closed in Madrid (Wed 01:30), still Tue 20:30 in Buenos Aires.
        var late = Instant.parse("2026-08-04T23:30:00Z");
        assertThat(refused(PENDING, PRESENT, BookingState.ACTIVE, madrid, late)).isEqualTo(ErrorCode.ATTENDANCE_WINDOW_CLOSED);
        assertThat(decide(PENDING, PRESENT, BookingState.ACTIVE, buenosAires, late).effect()).isEqualTo(Effect.MARK);
        assertThat(refused(PENDING, PRESENT, BookingState.ACTIVE, buenosAires, Instant.parse("2026-08-05T03:00:00Z"))).isEqualTo(ErrorCode.ATTENDANCE_WINDOW_CLOSED);
    }

    /** Every row of the §5 transition table, inside the window, with a live booking. */
    @ParameterizedTest(name = "{0} → {1} = {2}")
    @CsvSource({"PENDING,PRESENT,MARK", "PENDING,NO_SHOW,MARK", "PENDING,NOTIFIED,NOTICE_CANCEL", "PRESENT,PENDING,MARK", "PRESENT,NO_SHOW,MARK",
            "NO_SHOW,PENDING,MARK", "NO_SHOW,PRESENT,MARK", "PRESENT,NOTIFIED,NOTICE_CANCEL", "NO_SHOW,NOTIFIED,NOTICE_CANCEL",
            "PENDING,PENDING,NONE", "PRESENT,PRESENT,NONE", "NO_SHOW,NO_SHOW,NONE", "NOTIFIED,NOTIFIED,NONE"})
    void T_10_02_everyTransitionOfTheTable(AttendanceState from, AttendanceState to, Effect effect) {
        var decision = decide(from, to, BookingState.ACTIVE, sheet(MADRID, false, "ACTIVE", true), local("2026-08-03T08:40", MADRID));
        assertThat(decision.effect()).isEqualTo(effect);
        assertThat(decision.changes()).isEqualTo(effect != Effect.NONE);
        assertThat(decision.override()).isFalse();
    }

    @Test void T_10_02_notifiedIsFinalAndEveryRefusalIsTheCatalogCode() {
        var inside = local("2026-08-03T08:40", MADRID); var instructor = sheet(MADRID, false, "ACTIVE", true);
        for (var to : new AttendanceState[] {PENDING, PRESENT, NO_SHOW}) {
            assertThat(refused(NOTIFIED, to, BookingState.CANCELLED, instructor, inside)).isEqualTo(ErrorCode.ATTENDANCE_NOTIFIED_FINAL);
            assertThat(refused(NOTIFIED, to, BookingState.ACTIVE, sheet(MADRID, true, "ACTIVE", true), inside)).isEqualTo(ErrorCode.ATTENDANCE_NOTIFIED_FINAL);
        }
        // Resending the NOTIFIED row as it is: a no-op, not an error.
        assertThat(decide(NOTIFIED, NOTIFIED, BookingState.CANCELLED, instructor, inside).changes()).isFalse();
        // The booking must be live; NOTIFIED needs it ACTIVE.
        var notActive = catchThrowableOfType(ApiException.class, () -> decide(PENDING, PRESENT, BookingState.CANCELLED, instructor, inside));
        assertThat(notActive.code()).isEqualTo(ErrorCode.ATTENDANCE_BOOKING_NOT_ACTIVE);
        assertThat(notActive.details()).isEqualTo(Map.of("bookingId", "b1"));
        assertThat(decide(PENDING, PRESENT, BookingState.PAYMENT_PENDING, instructor, inside).effect()).isEqualTo(Effect.MARK);
        assertThat(refused(PENDING, NOTIFIED, BookingState.PAYMENT_PENDING, instructor, inside)).isEqualTo(ErrorCode.ATTENDANCE_BOOKING_NOT_ACTIVE);
        assertThat(refused(PENDING, NOTIFIED, BookingState.CANCELLED_LATE, instructor, inside)).isEqualTo(ErrorCode.ATTENDANCE_BOOKING_NOT_ACTIVE);
        // bookings.instructorLastMinuteNotice = false.
        assertThat(refused(PENDING, NOTIFIED, BookingState.ACTIVE, sheet(MADRID, false, "ACTIVE", false), inside)).isEqualTo(ErrorCode.INSTRUCTOR_NOTICE_DISABLED);
        assertThat(refused(PENDING, NOTIFIED, BookingState.ACTIVE, sheet(MADRID, true, "ACTIVE", false), inside)).isEqualTo(ErrorCode.INSTRUCTOR_NOTICE_DISABLED);
        // A DRAFT or CANCELLED class accepts nothing (409), a FINISHED one until T1.
        for (var state : new String[] {"DRAFT", "CANCELLED"}) {
            assertThat(refused(PENDING, PRESENT, BookingState.ACTIVE, sheet(MADRID, true, state, true), inside)).isEqualTo(ErrorCode.INVALID_STATE);
            assertThat(refused(PENDING, PENDING, BookingState.ACTIVE, sheet(MADRID, true, state, true), inside)).isEqualTo(ErrorCode.INVALID_STATE);
        }
        assertThat(ErrorCode.INVALID_STATE.httpStatus()).isEqualTo(409);
        assertThat(decide(PENDING, PRESENT, BookingState.ACTIVE, sheet(MADRID, false, "FINISHED", true), local("2026-08-04T22:00", MADRID)).effect()).isEqualTo(Effect.MARK);
    }

    @Test void T_10_02_notifiedBeforeTheClassEndCancelsAfterItOnlyRecordsAndAfterT1IsRefusedForEveryRole() {
        var instructor = sheet(MADRID, false, "ACTIVE", true); var admin = sheet(MADRID, true, "FINISHED", true);
        // NOTIFIED has no T0 bound: the member may phone the day before.
        assertThat(decide(PENDING, NOTIFIED, BookingState.ACTIVE, instructor, local("2026-08-02T18:00", MADRID)).effect()).isEqualTo(Effect.NOTICE_CANCEL);
        assertThat(decide(PENDING, NOTIFIED, BookingState.ACTIVE, instructor, local("2026-08-03T09:29:59", MADRID)).effect()).isEqualTo(Effect.NOTICE_CANCEL);
        assertThat(decide(PENDING, NOTIFIED, BookingState.ACTIVE, instructor, local("2026-08-03T09:30", MADRID)).effect()).isEqualTo(Effect.NOTICE_RECORD);
        assertThat(decide(PRESENT, NOTIFIED, BookingState.ACTIVE, admin, local("2026-08-04T23:59:59", MADRID)).effect()).isEqualTo(Effect.NOTICE_RECORD);
        assertThat(refused(PENDING, NOTIFIED, BookingState.ACTIVE, admin, local("2026-08-05T00:00", MADRID))).isEqualTo(ErrorCode.ATTENDANCE_WINDOW_CLOSED);
        assertThat(decide(PENDING, NOTIFIED, BookingState.ACTIVE, admin, local("2026-08-03T09:00", MADRID)).override()).isFalse();
    }

    @Test void T_10_01_sheetFlagsPaintTheCircles() {
        var window = AttendanceWindow.of(MONDAY, MADRID, 1);
        assertThat(window.canMarkPresence(false, local("2026-08-02T23:59", MADRID))).isFalse();
        assertThat(window.canMarkPresence(true, local("2026-08-02T23:59", MADRID))).isTrue();
        assertThat(window.canMarkNotice(true, local("2026-08-02T23:59", MADRID))).isTrue();
        assertThat(window.canMarkNotice(false, local("2026-08-03T08:00", MADRID))).isFalse();
        assertThat(window.canMarkNotice(true, local("2026-08-05T00:00", MADRID))).isFalse();
        assertThat(window.overrides(false, local("2026-08-05T00:00", MADRID))).isFalse();
    }
}
