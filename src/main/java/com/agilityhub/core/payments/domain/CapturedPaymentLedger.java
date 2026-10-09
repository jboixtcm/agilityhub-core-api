package com.agilityhub.core.payments.domain;

/**
 * The money of one captured Stripe payment (S12 R-12-20, ruling E97), in minor units of its currency:
 * {@code captured} = C, {@code refunded} = S (refunds Stripe settled), {@code reserved} = R (refund commands neither settled
 * nor failed/canceled), {@code credited} = K (the CREDIT compensation, a negative {@code PendingCharge}).
 * <p>
 * Invariants, checked after every step of the generated sequences in {@code CapturedPaymentInvariantsIT}:
 * <ol>
 * <li><b>Conservation.</b> S, R, K ≥ 0 and S + R + K + D = C, where D = {@link #free()} ≥ 0 is what the club still holds. Once a
 * cancellation obligation exists, D is what the member is still owed: the obligation drives it to 0, or keeps it recorded as an
 * intervention (an admin repays it by hand). An admin refund can never take more than D. A refund made in Stripe's dashboard
 * after a CREDIT shrinks the unbilled credit by S + R + K beyond C (including pending refund reservations); a billed credit cannot shrink, and the overpaid
 * amount is recorded as an intervention (E8-T11).</li>
 * <li><b>One compensation per cause.</b> A payment has at most one cancellation obligation (the first of REFUND or CREDIT wins), at
 * most one credit row, one compensation refund command plus one supplement per other refund that failed after it, and none after
 * a compensation refund itself failed: Stripe's failure or a refusal of its content is terminal and becomes an intervention, which
 * the admin learns through N-55 (APP and EMAIL) and the member's audit trail. The owed total stays current. An outage of the club's Stripe access never fails one: it waits (E8-T11).</li>
 * <li><b>Stripe wins.</b> A refund webhook always commits its reconciliation, whatever the provider's availability: compensation
 * bookkeeping never calls the provider, and a failure never lets a redelivered older success apply.</li>
 * </ol>
 */
public record CapturedPaymentLedger(long captured, long refunded, long reserved, long credited) {
    /** D: captured money neither refunded, reserved for a refund nor credited. */
    public long free() { return captured - refunded - reserved - credited; }
    /** Invariant 1, for the checker and for defensive callers. */
    public boolean consistent() { return captured >= 0 && refunded >= 0 && reserved >= 0 && credited >= 0 && free() >= 0; }
    /** What a compensation may still add: never negative, even when an external Stripe refund overshot the ledger. */
    public long due() { return Math.max(0, free()); }
}
