package com.agilityhub.core.platform.application.privacy;

/** S14 §3 `ErasureRequest.blockReasons` (R-14-14): why a member's data cannot be erased yet. */
public enum ErasureBlockReason {
    MEMBER_NOT_LEFT, INVOICES_OPEN, UPFRONT_OPEN, FUTURE_BOOKINGS, OTHER_MEMBERSHIPS_ACTIVE,
    /**
     * PRIV-04 (owner's decision of 13-11-2026): a SEPA collection of the member is still inside its 8-week refund window. It does
     * not refuse a request: the request waits `BLOCKED` with this reason and runs when the window closes.
     */
    SEPA_REFUND_WINDOW;

    /** Whether a request is accepted and waits with this reason instead of being refused (`422 ERASURE_BLOCKED`). */
    public boolean waits() { return this == SEPA_REFUND_WINDOW; }
}
