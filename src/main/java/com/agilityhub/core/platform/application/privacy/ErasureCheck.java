package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * S14 R-14-14 `ErasureEligibility`: one context's reasons not to erase these members of the current club yet (payments: open
 * invoices and upfront payments; bookings and training: future active bookings). Empty = nothing blocks.
 */
public interface ErasureCheck {
    Set<ErasureBlockReason> blockReasons(Collection<String> memberIds, Instant now);
}
