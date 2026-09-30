package com.agilityhub.core.clubs.common.application.ports;

import java.time.Instant;
import java.util.List;

/**
 * S15 R-15-15 step h and S04 R-04-26 (ruling E80): how P5 reaches the club's signup checkouts, as P7 reaches a booking's
 * checkout through the bookings' `SingleClassChargePort`. The default adapter ({@code SignupCheckoutExpiries}) runs over
 * `payments.application.CheckoutService`; S12 WP-12-D may replace it in E8.
 */
public interface LapsedCheckoutsPort {
    /** An open signup checkout whose `expiresAt` has passed: the provider can no longer complete it. */
    record Lapsed(String checkoutSessionId, Instant expiresAt) { }
    /** The club's open (`PENDING`) signup checkouts whose `expiresAt` is not after {@code now}, oldest first. */
    List<Lapsed> lapsed(Instant now);
    /**
     * The signup checkout {@code checkoutSessionId}, still open past its `expiresAt` at {@code now}, expires and gives its rows
     * back (`DUE`, or `PARTIAL`), whether the provider ever had its session or not, and without asking the provider. False when
     * it is no longer in scope (closed meanwhile, or not past its expiry).
     */
    boolean expire(String checkoutSessionId, Instant now);
}
