package com.agilityhub.core.clubs.bookings.domain;

import java.time.LocalDate;

/**
 * State of the 04 pack card (S08 §6 `pack.state`): EMPTY without sessions left, EXPIRED once `expiresOn` is before
 * today, EXPIRING inside the N-11 warnings (`billing.packLowBalanceSessions` sessions left or fewer, or expiring within
 * `billing.packExpiryWarningDays`), otherwise ACTIVE. Row state PACK_EMPTY stays {@link BookingEligibility#packEmpty}.
 */
public final class PackCard {
    public enum State { ACTIVE, EXPIRING, EXPIRED, EMPTY }
    private PackCard() { }
    public static State state(int available, LocalDate expiresOn, LocalDate today, int lowBalanceSessions, int expiryWarningDays) {
        if (available <= 0) { return State.EMPTY; }
        if (expiresOn != null && expiresOn.isBefore(today)) { return State.EXPIRED; }
        if (available <= lowBalanceSessions || expiresOn != null && !expiresOn.isAfter(today.plusDays(expiryWarningDays))) { return State.EXPIRING; }
        return State.ACTIVE;
    }
}
