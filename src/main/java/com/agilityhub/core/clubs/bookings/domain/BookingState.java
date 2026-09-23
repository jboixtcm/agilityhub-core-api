package com.agilityhub.core.clubs.bookings.domain;

/** S08 §5: CANCELLED_LATE counts towards the weekly limit; CANCELLED and CANCELLED_BY_CLUB do not (R-08-02). */
public enum BookingState { PAYMENT_PENDING, ACTIVE, CANCELLED, CANCELLED_LATE, CANCELLED_BY_CLUB }
