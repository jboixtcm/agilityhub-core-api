package com.agilityhub.core.clubs.bookings.application.fixtures;

import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;

/** E5-T14: an S08 class that is not the named counter writer; `COUNTER_WRITERS` must reject it (test sources only). */
public final class CounterWriterFixture {
    public Object run(ClassSessionBookingAccess classes) { return classes.counters("class", 1, 0, ClassSessionBookingAccess.LowAlert.KEEP); }
}
