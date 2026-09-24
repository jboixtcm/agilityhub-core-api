package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.AttendanceWindow;
import com.agilityhub.core.clubs.scheduling.application.ports.AttendanceStatusPort;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * S10 R-10-03 window and `attendance.status` in the club's time zone with `attendance.editDays`. One helper for
 * `GET /instructor/day` (S10) and, through {@link AttendanceStatusPort}, the S06 calendar and day grid.
 */
@Service
public class AttendanceStatusCalculator implements AttendanceStatusPort {
    private final BookingContext context;
    public AttendanceStatusCalculator(BookingContext context) { this.context = context; }

    public AttendanceWindow window(LocalDate classDate) { return AttendanceWindow.of(classDate, context.zone(), context.integer("attendance.editDays")); }

    @Override public AttendanceStatus status(LocalDate classDate, String classState, int marked, int booked, int notified, int notifiedAfterEnd) {
        return AttendanceStatus.valueOf(window(classDate).status(context.now(), classState, marked, total(booked, notified, notifiedAfterEnd)).name());
    }

    /**
     * R-10-02 union, each row once: a NOTIFIED row after the class end keeps its booking ACTIVE (R-10-05), so it is
     * already in `booked`; only the NOTIFIED rows that released their seat are added.
     */
    public static int total(int booked, int notified, int notifiedAfterEnd) { return booked + notified - notifiedAfterEnd; }
}
