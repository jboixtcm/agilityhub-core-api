package com.agilityhub.core.platform.application.privacy;

/**
 * S14 R-14-15: one context's pseudonymisation of the subject's documents in the current club. It runs inside the club's erasure
 * transaction, is idempotent (a second run changes nothing), and returns the files to delete once that transaction commits.
 */
public interface ErasureStep {
    ErasureEffect erase(ErasureSubject subject);
}
