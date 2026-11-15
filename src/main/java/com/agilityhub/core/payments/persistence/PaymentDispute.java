package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.Set;

/**
 * PAY-06 (owner decision of 13 Nov 2026, S12 R-12-21): the Stripe dispute (chargeback) of a card payment, kept on the payment it
 * disputes (`UpfrontPayment.dispute`, `Collection.dispute`) as the last `charge.dispute.*` event left it: Stripe's dispute id
 * ({@code providerRef}, as a refund's), its {@code status} and {@code reason}, the disputed {@code amount}, when Core learnt of
 * it ({@code openedAt}) and when Stripe closed it ({@code closedAt}, with `won`, `lost` or `warning_closed`). It is a flag only:
 * no payment, pack, booking or invoice state changes with it. (Not `id`: Spring Data would store an embedded `id` as `_id`.)
 */
public record PaymentDispute(String providerRef, String status, String reason, Money amount, Instant openedAt, Instant closedAt) {
    /** Stripe's final dispute statuses: a later event never reopens a dispute that reached one. */
    public static final Set<String> CLOSED = Set.of("won", "lost", "warning_closed");
    public boolean closed() { return CLOSED.contains(status); }
}
