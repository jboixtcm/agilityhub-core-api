package com.agilityhub.core.arch.api;

import com.agilityhub.core.clubs.bookings.application.BookingContext;
import java.time.Instant;

/** E5-T15 (review E5-T06 #4): a seeder name in an `..api..` package may not move the booking time. */
public final class DemoRuleApiSeeder {
    Object asOf(BookingContext context) { return context.asOf(Instant.EPOCH, () -> "work"); }
}
