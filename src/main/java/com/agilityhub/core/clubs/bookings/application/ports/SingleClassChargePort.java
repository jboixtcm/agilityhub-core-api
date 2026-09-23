package com.agilityhub.core.clubs.bookings.application.ports;

import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.shared.domain.Money;
import java.util.Optional;

/**
 * S08 R-08-18 (module SINGLE_CLASS). `terms` gives the owner's single-class charge (plan type SINGLE_CLASS);
 * `checkout` opens the payment of a PAY_TO_BOOK booking. The default adapter ({@code SingleClassCharges}) runs over
 * `payments.application` (`UpfrontPayments` + `PaymentProvider`); S12 WP-12-D replaces it in E8.
 */
public interface SingleClassChargePort {
    record Terms(ChargeMode mode, Money price) { }
    record Checkout(String sessionId, String url) { }
    Optional<Terms> terms(String ownerMemberId);
    Checkout checkout(String ownerMemberId, String dogId, String bookingId, Money price, String description);
}
