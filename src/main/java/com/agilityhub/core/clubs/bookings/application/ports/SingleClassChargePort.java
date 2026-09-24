package com.agilityhub.core.clubs.bookings.application.ports;

import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.Optional;

/**
 * S08 R-08-18 (module SINGLE_CLASS). `terms` gives the owner's single-class charge (plan type SINGLE_CLASS). A
 * PAY_TO_BOOK checkout has two halves so the provider is never called inside the retried booking transaction:
 * `prepare` (Mongo only, inside the transaction that creates the PAYMENT_PENDING booking) and `open` (after the
 * commit, once, no seat lock held); `abandon` expires a checkout whose provider call failed. The default adapter
 * ({@code SingleClassCharges}) runs over `payments.application` (`CheckoutService` + `PaymentProvider`); S12 WP-12-D
 * replaces it in E8.
 */
public interface SingleClassChargePort {
    record Terms(ChargeMode mode, Money price) { }
    record Pending(String sessionId, String paymentId, String ownerMemberId, String bookingId, Money price, String description, Instant expiresAt) { }
    Optional<Terms> terms(String ownerMemberId);
    /** Inside the booking transaction: the due line and the PENDING session; `501 NOT_IMPLEMENTED` without a payment provider. */
    Pending prepare(String ownerMemberId, String dogId, String bookingId, Money price, String description);
    /** After the commit: asks the provider for the checkout of `pending.sessionId()` and returns its URL. */
    String open(Pending pending);
    /** The provider call failed: the session expires and its line is cancelled (`UpfrontPaymentFailed{bookingId}`). */
    void abandon(Pending pending);
}
