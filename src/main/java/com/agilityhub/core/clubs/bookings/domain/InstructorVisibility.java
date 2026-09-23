package com.agilityhub.core.clubs.bookings.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * S08 R-08-20: members see the instructor only from `classStartsAt − bookings.showInstructorHoursBefore` absolute
 * hours on (`0` = always); before that the name is null and `instructorVisibleAt` says when. Reused by E5-T06.
 */
public final class InstructorVisibility {
    public record View(String instructorName, Instant instructorVisibleAt) { }
    private InstructorVisibility() { }
    public static Instant visibleAt(Instant classStartsAt, int hoursBefore) {
        return hoursBefore == 0 ? null : classStartsAt.minus(Duration.ofHours(hoursBefore));
    }
    public static boolean visible(Instant now, Instant classStartsAt, int hoursBefore) {
        var from = visibleAt(classStartsAt, hoursBefore);
        return from == null || !now.isBefore(from);
    }
    /** Staff always see the name; members see it once visible, and otherwise get the instant it becomes visible. */
    public static View of(String instructorName, Instant now, Instant classStartsAt, int hoursBefore, boolean staff) {
        if (staff || visible(now, classStartsAt, hoursBefore)) { return new View(instructorName, null); }
        return new View(null, visibleAt(classStartsAt, hoursBefore));
    }
}
