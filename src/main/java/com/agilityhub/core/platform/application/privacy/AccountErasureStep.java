package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;

/**
 * S14 R-14-15, the global part: one context's pseudonymisation of an erased account's documents outside any club (the account,
 * its tokens and its own courses), inside the transaction of the club that completes the request.
 */
public interface AccountErasureStep {
    ErasureEffect eraseAccount(String accountId, String pseudonym, ErasurePlaceholders placeholders, Instant at);
}
