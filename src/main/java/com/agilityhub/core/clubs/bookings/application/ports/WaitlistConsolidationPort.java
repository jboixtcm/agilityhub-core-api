package com.agilityhub.core.clubs.bookings.application.ports;

import com.agilityhub.core.clubs.bookings.application.BookingActor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * S08 R-08-08/R-08-15, called inside the booking confirmation transaction: the dog's live waiting-list entry of the
 * class becomes CONSOLIDATED (`WaitlistConsolidated`) — through a claim, or `BOOKED_DIRECTLY` — and, in
 * `ALL_AT_ONCE`, a booking that leaves no free seat demotes the other NOTIFIED entries back to ACTIVE (R-08-13).
 * Implemented by `WaitlistTransitions` (E5-T03), in this same context.
 */
public interface WaitlistConsolidationPort {
    /** @param claim the booking comes from `POST /waitlist-entries/{id}/claim` (otherwise a direct booking) — returns the entry id */
    Optional<String> consolidate(String classSessionId, String dogId, String bookingId, boolean claim, BookingActor actor);
    /** Returns the ids of the demoted entries (empty unless WAITLIST, ALL_AT_ONCE and no free seat). */
    List<String> demoteIfFull(String classSessionId, BookingActor actor);
    /**
     * R-08-14 (INC-40, E11-T10): the class's open FIFO offers (NOTIFIED, `confirmBy > now`) in queue order; their seats are taken
     * for everybody else. Empty in ALL_AT_ONCE and with WAITLIST off.
     */
    List<Offer> openOffers(String classSessionId, Instant now);
    /** One open FIFO offer; the queue order is (`notifiedAt`, `position`). */
    record Offer(String entryId, String dogId, Instant notifiedAt, int position) { }
}
