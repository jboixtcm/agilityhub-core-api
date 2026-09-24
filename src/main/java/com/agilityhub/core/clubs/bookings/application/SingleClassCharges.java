package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.payments.application.CheckoutService;
import com.agilityhub.core.payments.application.PaymentProvider;
import com.agilityhub.core.payments.application.UpfrontPayments;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Default {@link SingleClassChargePort} over the existing `payments.application` code until S12 WP-12-D (E8):
 * terms from the owner's plan (`type = SINGLE_CLASS`, `singleClass.chargeMode`) and price; a PAY_TO_BOOK checkout is
 * one `UpfrontPayment` line (`concept = SINGLE_CLASS`, `bookingId`) plus a `checkout_sessions` row expiring after
 * `bookings.paymentPendingMinutes`, and then `PaymentProvider.createCheckoutSession` carrying the `bookingId`. The
 * provider's completion or expiry (`CheckoutService.complete` / `expire`, `FakeCheckoutGateway` in local/test) emits
 * `UpfrontPaymentSucceeded` / `UpfrontPaymentFailed` with that `bookingId` for {@link BookingConsumers}.
 */
public class SingleClassCharges implements SingleClassChargePort {
    private final BookingMemberAccess census; private final CheckoutService checkouts; private final ObjectProvider<PaymentProvider> gateways;
    private final BookingContext context; private final CensusClubSettings clubs;
    public SingleClassCharges(BookingMemberAccess census, CheckoutService checkouts, ObjectProvider<PaymentProvider> gateways, BookingContext context, CensusClubSettings clubs) {
        this.census = census; this.checkouts = checkouts; this.gateways = gateways; this.context = context; this.clubs = clubs;
    }
    @Override public Optional<Terms> terms(String ownerMemberId) {
        var plan = census.planTerms(ownerMemberId);
        if (!"SINGLE_CLASS".equals(plan.type()) || plan.chargeMode() == null || plan.price() == null) { return Optional.empty(); }
        return Optional.of(new Terms(ChargeMode.valueOf(plan.chargeMode()), plan.price()));
    }
    @Override public Pending prepare(String ownerMemberId, String dogId, String bookingId, Money price, String description) {
        // The same guards as the signup checkout (CheckoutService.create): the club has the provider enabled, and a gateway exists.
        if (!clubs.providerEnabled("STRIPE")) { throw new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED); }
        if (gateways.getIfAvailable() == null) { throw new ApiException(ErrorCode.NOT_IMPLEMENTED); }
        var expiresAt = context.now().plus(Duration.ofMinutes(context.integer("bookings.paymentPendingMinutes")));
        var checkout = checkouts.prepareBooking(ownerMemberId, bookingId, new UpfrontPayments.Charge("SINGLE_CLASS", dogId, price), expiresAt);
        return new Pending(checkout.sessionId(), checkout.paymentId(), ownerMemberId, bookingId, price, description, expiresAt);
    }
    @Override public String open(Pending pending) {
        String base;
        try { base = "https://" + clubs.appHost() + "/reserves/" + pending.bookingId(); } catch (ApiException noVerifiedHost) { base = "/reserves/" + pending.bookingId(); }
        var member = census.member(pending.ownerMemberId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var request = new PaymentProvider.Request(pending.sessionId(), TenantContext.require(), pending.ownerMemberId(), "payment",
                List.of(new PaymentProvider.Item(pending.paymentId(), pending.description(), pending.price())), member.email(), pending.bookingId(),
                Map.of("clubId", TenantContext.require(), "memberId", pending.ownerMemberId(), "bookingId", pending.bookingId(), "upfrontPaymentIds", List.of(pending.paymentId())),
                null, base + "?payment=success", base + "?payment=cancel", pending.expiresAt());
        return gateways.getObject().createCheckoutSession(request);
    }
    @Override public void abandon(String checkoutSessionId) { checkouts.expire(checkoutSessionId); }
}
