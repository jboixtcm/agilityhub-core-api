package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * S14 R-14-14 `ErasureEligibility`: one context's reasons not to erase these members of the current club yet (payments: open
 * invoices and upfront payments, and a SEPA collection inside its refund window; bookings and training: future active bookings).
 * Empty = nothing blocks.
 */
public interface ErasureCheck {
    Set<ErasureBlockReason> blockReasons(Collection<String> memberIds, Instant now);

    /**
     * PRIV-04: when the reasons of this context that wait ({@link ErasureBlockReason#waits}) stop holding, if nothing changes
     * meanwhile (payments: the first day the last SEPA refund window is closed); empty when none holds.
     */
    default Optional<Instant> waitsUntil(Collection<String> memberIds, Instant now) { return Optional.empty(); }
}
