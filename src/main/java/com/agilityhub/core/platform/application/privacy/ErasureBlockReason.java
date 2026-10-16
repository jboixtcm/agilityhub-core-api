package com.agilityhub.core.platform.application.privacy;

/** S14 §3 `ErasureRequest.blockReasons` (R-14-14): why a member's data cannot be erased yet. */
public enum ErasureBlockReason { MEMBER_NOT_LEFT, INVOICES_OPEN, UPFRONT_OPEN, FUTURE_BOOKINGS, OTHER_MEMBERSHIPS_ACTIVE }
