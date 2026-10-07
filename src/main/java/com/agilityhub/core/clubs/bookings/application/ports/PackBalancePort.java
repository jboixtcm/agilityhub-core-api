package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.LocalDate;
import java.util.Optional;

/**
 * S08 R-08-17 over S12 `PackBalanceService` (WP-12-E, E8, decision B11). Called inside the booking transaction:
 * the real adapter emits `PackConsumed` / `PackRefunded` in that same transaction. An empty balance means packs
 * do not apply to the dog (module off, not a PACK plan). A refund into an expired pack does not revive it: the
 * adapter records a REFUND movement on the EXPIRED balance, which remains unusable.
 */
public interface PackBalancePort {
    record Balance(String id, int total, int consumed, int available, LocalDate expiresOn) { }
    Optional<Balance> balance(String memberId, String dogId);
    default Optional<Balance> balance(String memberId, String dogId, LocalDate classDate) { return balance(memberId, dogId); }
    default String consume(String memberId, String dogId, String bookingId, LocalDate classDate) { return consume(memberId, dogId, bookingId); }
    /** Consumes one session; returns the movement id. */
    String consume(String memberId, String dogId, String bookingId);
    /** Returns one previously consumed session even after expiry; returns the movement id, or null on replay. */
    String refund(String memberId, String dogId, String bookingId, LocalDate today);
}
