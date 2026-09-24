package com.agilityhub.core.clubs.bookings.domain;

/**
 * S08 §3 `WaitlistEntry.cancelReason`; BOOKED_DIRECTLY ends as CONSOLIDATED (R-08-16). MEMBER_LEFT is the S15 §13
 * catalog proposal used by `WaitlistService.cancelByMember` (S15 P5c / S13 leave handling).
 */
public enum WaitlistCancelReason { MEMBER, CLASS_CANCELLED, CLASS_STARTED, ADMIN, BOOKED_DIRECTLY, MEMBER_LEFT }
