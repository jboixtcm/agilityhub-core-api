package com.agilityhub.core.clubs.common.application.ports;

import java.time.Instant;
import java.util.List;

/**
 * S15 R-15-15 step f and S16 R-16-08: how P5 reaches the club's ring setups. S16 (courses) supplies the adapter at the composition
 * root; `clubs.common` never imports `courses`.
 */
public interface LapsedRingSetupsPort {
    /** An `ACTIVE` setup whose `expiresAt` has passed. */
    record Lapsed(String setupId, String ringId, Instant expiresAt) { }
    /** The club's `ACTIVE` setups whose `expiresAt` is not after {@code now}, oldest expiry first. */
    List<Lapsed> lapsed(Instant now);
    /**
     * The setup {@code setupId}, still `ACTIVE` and lapsed at {@code now}, becomes `EXPIRED`, `Ring.activeSetupId = null` when it
     * still names it, and `RingSetupChanged{EXPIRED}` is published. False when it is no longer in scope (ended or renewed meanwhile).
     */
    boolean expire(String setupId, Instant now);
}
