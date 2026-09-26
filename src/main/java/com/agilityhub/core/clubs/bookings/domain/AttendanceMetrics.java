package com.agilityhub.core.clubs.bookings.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * S10 R-10-08 (30-day metrics of 22/D13) and R-10-09 (the last classes), over the class bookings of one dog with their
 * S10 attendance. The window `W = [from, now)` is on `Booking.classStartsAt`; `from` is `now − 30 days` in the club's
 * time zone (the caller computes it). The window and the 5 classes are product constants, not club parameters (§9).
 */
public final class AttendanceMetrics {
    public static final int WINDOW_DAYS = 30;
    public static final int LAST_CLASSES = 5;
    private AttendanceMetrics() { }
    /** One class booking of the dog; `attendance` is PENDING without an `Attendance` document. */
    public record ClassRecord(String bookingId, Instant classStartsAt, BookingState bookingState, AttendanceState attendance, boolean afterClassEnd) { }
    /** The 22/D13 badges: «present» · «avisat» · «no presentat» · «anul·lada tard» · «sense marcar» (assumption). */
    public enum Display { PRESENT, NOTIFIED, NO_SHOW, CANCELLED_LATE, PENDING }
    public record Metrics(int windowDays, Integer attendancePct, int present, int noShow, int notified, int cancelledLate, int classesCounted) { }
    public record LastClass(ClassRecord record, Display display) { }

    /**
     * `present` = PRESENT; `noShow` = NO_SHOW; `notified` = in-time «ha avisat» (booking CANCELLED, informative);
     * `cancelledLate` = CANCELLED_LATE bookings (the member's, or a late «ha avisat») plus `NOTIFIED{afterClassEnd}`;
     * `classesCounted = present + noShow + cancelledLate` (assumption §13-1: late cancellations are in the denominator);
     * `attendancePct = round(100 · present / classesCounted)`, null when it is 0. ACTIVE bookings without a mark,
     * in-time cancellations of the member or the system and CANCELLED_BY_CLUB count nowhere.
     */
    public static Metrics of(List<ClassRecord> records, Instant from, Instant now) {
        int present = 0, noShow = 0, notified = 0, cancelledLate = 0;
        for (var r : records) {
            if (r.classStartsAt().isBefore(from) || !r.classStartsAt().isBefore(now)) { continue; }
            switch (kind(r)) {
                case PRESENT -> present++;
                case NO_SHOW -> noShow++;
                case NOTIFIED_IN_TIME -> notified++;
                case CANCELLED_LATE -> cancelledLate++;
                case NONE -> { }
            }
        }
        int counted = present + noShow + cancelledLate;
        return new Metrics(WINDOW_DAYS, counted == 0 ? null : (int) Math.round(100.0 * present / counted), present, noShow, notified, cancelledLate, counted);
    }
    private enum Kind { PRESENT, NO_SHOW, NOTIFIED_IN_TIME, CANCELLED_LATE, NONE }
    private static Kind kind(ClassRecord r) {
        if (r.bookingState() == BookingState.CANCELLED_BY_CLUB) { return Kind.NONE; }
        if (r.attendance() == AttendanceState.NOTIFIED) {
            if (r.afterClassEnd() || r.bookingState() == BookingState.CANCELLED_LATE) { return Kind.CANCELLED_LATE; }
            return r.bookingState() == BookingState.CANCELLED ? Kind.NOTIFIED_IN_TIME : Kind.NONE;
        }
        if (r.bookingState() == BookingState.CANCELLED_LATE) { return Kind.CANCELLED_LATE; }
        if (r.bookingState() != BookingState.ACTIVE) { return Kind.NONE; }
        return r.attendance() == AttendanceState.PRESENT ? Kind.PRESENT : r.attendance() == AttendanceState.NO_SHOW ? Kind.NO_SHOW : Kind.NONE;
    }

    /** `trainingsPerWeek = round1(trainingsCount / (30/7))`: 10 trainings → 2.3 (R-10-08). */
    public static double perWeek(int trainingsCount) { return Math.round(trainingsCount * 7.0 / WINDOW_DAYS * 10) / 10.0; }

    /**
     * R-10-09: the 5 most recent bookings with `classStartsAt ≤ now` that are ACTIVE, CANCELLED_LATE or «ha avisat»,
     * newest first; CANCELLED (the member's or the system's in-time cancellation), CANCELLED_BY_CLUB, PAYMENT_PENDING
     * and future classes are left out.
     */
    public static List<LastClass> lastClasses(List<ClassRecord> records, Instant now) {
        return records.stream().filter(r -> !r.classStartsAt().isAfter(now))
                .map(r -> display(r).map(d -> new LastClass(r, d))).flatMap(Optional::stream)
                .sorted(Comparator.comparing((LastClass l) -> l.record().classStartsAt()).reversed().thenComparing(l -> l.record().bookingId()))
                .limit(LAST_CLASSES).toList();
    }
    public static Optional<Display> display(ClassRecord r) {
        if (r.attendance() == AttendanceState.NOTIFIED && r.bookingState() != BookingState.CANCELLED_BY_CLUB) { return Optional.of(Display.NOTIFIED); }
        return switch (r.bookingState()) {
            case CANCELLED_LATE -> Optional.of(Display.CANCELLED_LATE);
            case ACTIVE -> Optional.of(r.attendance() == AttendanceState.PRESENT ? Display.PRESENT
                    : r.attendance() == AttendanceState.NO_SHOW ? Display.NO_SHOW : Display.PENDING);
            default -> Optional.empty();
        };
    }
}
