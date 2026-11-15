package com.agilityhub.core.payments.domain;

import java.time.LocalDate;
import java.time.Period;

/**
 * PRIV-04 (owner's decision of 13-11-2026; S14 R-14-14): the SEPA Core refund window. The debtor may claim an authorised direct
 * debit back from their bank for 8 weeks after the debit date (the remittance's requested collection date), so the club keeps
 * the payer's identity until that window has closed: a member's erasure waits for it.
 */
public final class SepaRefundWindow {
    /** The SEPA Core rulebook's refund right for an authorised debit: 8 weeks, the same for every club. */
    public static final Period LENGTH = Period.ofWeeks(8);
    private SepaRefundWindow() { }

    /** The earliest collection date whose refund window is still open on {@code today}: a debit on that date or later. */
    public static LocalDate openSince(LocalDate today) { return today.minus(LENGTH); }

    /** The first day on which a debit collected on {@code collectionDate} can no longer be claimed back: 8 weeks and a day later. */
    public static LocalDate closesOn(LocalDate collectionDate) { return collectionDate.plus(LENGTH).plusDays(1); }
}
