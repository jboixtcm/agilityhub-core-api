package com.agilityhub.core.clubs.bookings.domain;

/** S08 §3 `WaitlistEntry.cancelReason`; BOOKED_DIRECTLY ends as CONSOLIDATED (R-08-16). */
public enum WaitlistCancelReason { MEMBER, CLASS_CANCELLED, CLASS_STARTED, ADMIN, BOOKED_DIRECTLY }
