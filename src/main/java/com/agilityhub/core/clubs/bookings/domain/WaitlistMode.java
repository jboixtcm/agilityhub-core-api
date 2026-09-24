package com.agilityhub.core.clubs.bookings.domain;

/** `waitlist.mode` (ADR-012): everybody at once (the Cànic) or one entry per free seat in `position` order. */
public enum WaitlistMode { ALL_AT_ONCE, FIFO }
