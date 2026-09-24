package com.agilityhub.core.clubs.scheduling.application.ports;

import java.time.LocalDate;

/**
 * S10 §7 contract with S06: the attendance status of a class, derived from `ClassSession.attendanceSummary`, as
 * `CalendarQuery` and `DayGridQuery` show it. Served by S10 (`clubs.bookings`), so scheduling never imports bookings.
 */
public interface AttendanceStatusPort {
    enum AttendanceStatus { NONE, PENDING, DONE, CLOSED }
    /**
     * `classState` is the S06 `ClassState` name; `booked` is `counters.booked`, `notified` and `notifiedAfterEnd` come
     * from the summary. The sheet rows (R-10-02) are the live bookings plus the NOTIFIED rows that released their seat.
     */
    AttendanceStatus status(LocalDate classDate, String classState, int marked, int booked, int notified, int notifiedAfterEnd);
}
