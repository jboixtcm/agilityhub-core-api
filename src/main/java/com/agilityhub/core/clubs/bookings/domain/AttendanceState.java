package com.agilityhub.core.clubs.bookings.domain;

/** S10 §5: a booking without an `Attendance` document is PENDING; NOTIFIED is final (R-10-03). */
public enum AttendanceState { PENDING, PRESENT, NOTIFIED, NO_SHOW }
