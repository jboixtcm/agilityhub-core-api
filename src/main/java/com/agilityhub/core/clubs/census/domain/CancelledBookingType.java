package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §3 `cancelledBookings[].type` (R-13-06, R-13-12). */
@Schema(enumAsRef = true)
public enum CancelledBookingType { CLASS, WAITLIST, TRAINING, ACTIVITY }
