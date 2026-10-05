package com.agilityhub.core.clubs.census.application.ports;

import java.time.LocalDate;
import java.util.List;

/** S13: query or cancel synchronously inside the census transaction, both dates inclusive and null end open. */
public interface BookingCancellationPort {
    List<LifecycleCancellation> inside(String memberId, LocalDate from, LocalDate to, boolean cancel, boolean leave);
}
