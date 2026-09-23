package com.agilityhub.core.clubs.bookings.application.ports;

import java.util.Optional;

/**
 * S08 R-08-08: a direct booking consolidates the dog's live waiting-list entry of the class (`CONSOLIDATED`,
 * `WaitlistConsolidated`) inside the booking transaction. The waiting-list service is E5-T03, in this same context;
 * this task ships the no-op default. Returns the consolidated entry id.
 */
public interface WaitlistConsolidationPort {
    Optional<String> consolidate(String classSessionId, String dogId, String bookingId);
}
