package com.agilityhub.core.clubs.messaging.application.ports;

import java.time.Instant;

/**
 * S11 R-11-11 `CLAIM_SEAT.enabled` (N-15), computed on read: the waiting-list entry is still `NOTIFIED` and, in FIFO, its
 * `confirmBy` is after `now` (S08 R-08-15).
 */
public interface WaitlistRelevancePort {
    boolean stillNotified(String waitlistEntryId, Instant now);
}
