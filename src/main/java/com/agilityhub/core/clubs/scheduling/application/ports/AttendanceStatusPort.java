package com.agilityhub.core.clubs.scheduling.application.ports;

import java.time.LocalDate;

/**
 * S10 §7 contract with S06: the attendance status of a class, derived from `ClassSession.attendanceSummary`, as
 * `CalendarQuery` and `DayGridQuery` show it. Served by S10 (`clubs.bookings`), so scheduling never imports bookings.
 */
public interface AttendanceStatusPort {
    enum AttendanceStatus { NONE, PENDING, DONE, CLOSED }
    /** `classState` is the S06 `ClassState` name; `total` the rows of the sheet (live bookings + seat-releasing NOTIFIED). */
    AttendanceStatus status(LocalDate classDate, String classState, int marked, int total);
}
