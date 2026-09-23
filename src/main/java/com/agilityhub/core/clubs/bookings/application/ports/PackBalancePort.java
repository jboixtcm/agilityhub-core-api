package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.LocalDate;
import java.util.Optional;

/**
 * S08 R-08-17 over S12 `PackBalanceService` (WP-12-E, E8, decision B11). Called inside the booking transaction:
 * the real adapter emits `PackConsumed` / `PackRefunded` in that same transaction. An empty balance means packs
 * do not apply to the dog (module off, not a PACK plan). A refund into an expired pack does not revive it: the
 * adapter returns null and the session is lost. Default: packs disabled; local/test: {@link InMemoryPackBalances}.
 */
public interface PackBalancePort {
    record Balance(String id, int total, int consumed, int available, LocalDate expiresOn) { }
    Optional<Balance> balance(String memberId, String dogId);
    /** Consumes one session; returns the movement id. */
    String consume(String memberId, String dogId, String bookingId);
    /** Returns one session unless the pack has expired by {@code today}; returns the movement id or null. */
    String refund(String memberId, String dogId, String bookingId, LocalDate today);
}
